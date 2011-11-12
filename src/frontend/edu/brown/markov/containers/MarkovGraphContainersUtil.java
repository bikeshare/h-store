package edu.brown.markov.containers;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONStringer;
import org.voltdb.catalog.Database;
import org.voltdb.catalog.Procedure;
import org.voltdb.utils.Pair;

import edu.brown.catalog.CatalogUtil;
import edu.brown.hashing.AbstractHasher;
import edu.brown.logging.LoggerUtil;
import edu.brown.logging.LoggerUtil.LoggerBoolean;
import edu.brown.markov.MarkovGraph;
import edu.brown.markov.MarkovUtil;
import edu.brown.statistics.Histogram;
import edu.brown.utils.ClassUtil;
import edu.brown.utils.CollectionUtil;
import edu.brown.utils.FileUtil;
import edu.brown.utils.PartitionEstimator;
import edu.brown.utils.ProfileMeasurement;
import edu.brown.utils.ThreadUtil;
import edu.brown.workload.TransactionTrace;
import edu.brown.workload.Workload;

public abstract class MarkovGraphContainersUtil {
    public static final Logger LOG = Logger.getLogger(MarkovGraphContainersUtil.class);
    public final static LoggerBoolean debug = new LoggerBoolean(LOG.isDebugEnabled());
    private final static LoggerBoolean trace = new LoggerBoolean(LOG.isTraceEnabled());
    static {
        LoggerUtil.attachObserver(LOG, debug, trace);
    }

    // ----------------------------------------------------------------------------
    // INSTANTATION METHODS
    // ----------------------------------------------------------------------------
    
    /**
     * Create the MarkovGraphsContainers for the given workload
     * @param args
     * @throws Exception
     */
    @SuppressWarnings("unchecked")
    public static <T extends MarkovGraphsContainer> Map<Integer, MarkovGraphsContainer> createMarkovGraphsContainers(final Database catalog_db, final Workload workload, final PartitionEstimator p_estimator, final Class<T> containerClass) throws Exception {
        final String className = containerClass.getSimpleName();
        
        final Map<Integer, MarkovGraphsContainer> markovs_map = new ConcurrentHashMap<Integer, MarkovGraphsContainer>();
        final List<Runnable> runnables = new ArrayList<Runnable>();
        final Set<Procedure> procedures = workload.getProcedures(catalog_db);
        final Histogram<Procedure> proc_h = new Histogram<Procedure>();
        final int num_transactions = workload.getTransactionCount();
        final int marker = Math.max(1, (int)(num_transactions * 0.10));
        final AtomicInteger finished_ctr = new AtomicInteger(0);
        final AtomicInteger txn_ctr = new AtomicInteger(0);
        final int num_threads = ThreadUtil.getMaxGlobalThreads();
        
        final Constructor<T> constructor = ClassUtil.getConstructor(containerClass, new Class<?>[]{Collection.class});
        final boolean is_global = containerClass.equals(GlobalMarkovGraphsContainer.class);
        
        final List<Thread> processing_threads = new ArrayList<Thread>();
        final LinkedBlockingDeque<Pair<Integer, TransactionTrace>> queues[] = (LinkedBlockingDeque<Pair<Integer, TransactionTrace>>[])new LinkedBlockingDeque<?>[num_threads];
        for (int i = 0; i < num_threads; i++) {
            queues[i] = new LinkedBlockingDeque<Pair<Integer, TransactionTrace>>();
        } // FOR
        
        // QUEUING THREAD
        final AtomicBoolean queued_all = new AtomicBoolean(false);
        runnables.add(new Runnable() {
            @Override
            public void run() {
                List<TransactionTrace> all_txns = new ArrayList<TransactionTrace>(workload.getTransactions());
                Collections.shuffle(all_txns);
                int ctr = 0;
                for (TransactionTrace txn_trace : all_txns) {
                    // Make sure it goes to the right base partition
                    Integer partition = null;
                    try {
                        partition = p_estimator.getBasePartition(txn_trace);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                    assert(partition != null) : "Failed to get base partition for " + txn_trace + "\n" + txn_trace.debug(catalog_db);
                    queues[ctr % num_threads].add(Pair.of(partition, txn_trace));
                    if (++ctr % marker == 0) LOG.info(String.format("Queued %d/%d transactions", ctr, num_transactions));
                } // FOR
                queued_all.set(true);
                
                // Poke all our threads just in case they finished
                for (Thread t : processing_threads) t.interrupt();
            }
        });
        
        // PROCESSING THREADS
        for (int i = 0; i < num_threads; i++) {
            final int thread_id = i;
            runnables.add(new Runnable() {
                @Override
                public void run() {
                    Thread self = Thread.currentThread();
                    processing_threads.add(self);

                    MarkovGraphsContainer markovs = null;
                    Pair<Integer, TransactionTrace> pair = null;
                    
                    while (true) {
                        try {
                            if (queued_all.get()) {
                                pair = queues[thread_id].poll();
                            } else {
                                pair = queues[thread_id].take();
                                
                                // Steal work
                                if (pair == null) {
                                    for (int i = 0; i < num_threads; i++) {
                                        if (i == thread_id) continue;
                                        pair = queues[i].take();
                                        if (pair != null) break;
                                    } // FOR
                                }
                            }
                        } catch (InterruptedException ex) {
                            continue;
                        }
                        if (pair == null) break;
                        
                        int partition = pair.getFirst();
                        TransactionTrace txn_trace = pair.getSecond();
                        Procedure catalog_proc = txn_trace.getCatalogItem(catalog_db);
                        long txn_id = txn_trace.getTransactionId();
                        try {
                            int map_id = (is_global ? MarkovUtil.GLOBAL_MARKOV_CONTAINER_ID : partition);
                            Object params[] = txn_trace.getParams();
                            
                            markovs = markovs_map.get(map_id);
                            if (markovs == null) {
                                synchronized (markovs_map) {
                                    markovs = markovs_map.get(map_id);
                                    if (markovs == null) {
                                        markovs = constructor.newInstance(new Object[]{procedures});
                                        markovs.setHasher(p_estimator.getHasher());
                                        markovs_map.put(map_id, markovs);
                                    }
                                } // SYNCH
                            }
                            
                            MarkovGraph markov = markovs.getFromParams(txn_id, map_id, params, catalog_proc);
                            synchronized (markov) {
                                markov.processTransaction(txn_trace, p_estimator);
                            } // SYNCH
                        } catch (Exception ex) {
                            LOG.fatal("Failed to process " + txn_trace, ex);
                            throw new RuntimeException(ex);
                        }
                        proc_h.put(catalog_proc);
                        
                        int global_ctr = txn_ctr.incrementAndGet();
                        if (debug.get() && global_ctr % marker == 0) {
                            LOG.debug(String.format("Processed %d/%d transactions",
                                                    global_ctr, num_transactions));
                        }
                    } // FOR
                    LOG.info(String.format("Processing thread finished creating %s [%d/%d]",
                                            className, finished_ctr.incrementAndGet(), num_threads));
                }
            });
        } // FOR
        LOG.info(String.format("Generating %s for %d partitions using %d threads",
                               className, CatalogUtil.getNumberOfPartitions(catalog_db), num_threads));
        ThreadUtil.runGlobalPool(runnables);
    
        LOG.info("Procedure Histogram:\n" + proc_h);
        MarkovGraphContainersUtil.calculateProbabilities(markovs_map);
        
        return (markovs_map);
    }

    /**
     * Construct all of the Markov graphs for a workload+catalog split by the txn's base partition
     * @param catalog_db
     * @param workload
     * @param p_estimator
     * @return
     */
    public static MarkovGraphsContainer createBasePartitionMarkovGraphsContainer(final Database catalog_db, final Workload workload, final PartitionEstimator p_estimator) {
        assert(workload != null);
        assert(p_estimator != null);
        
        Map<Integer, MarkovGraphsContainer> markovs_map = null;
        try {
            markovs_map = createMarkovGraphsContainers(catalog_db, workload, p_estimator, MarkovGraphsContainer.class);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        assert(markovs_map != null);
        
        // Combine in a single Container
        final MarkovGraphsContainer combined = new MarkovGraphsContainer();
        for (Integer p : markovs_map.keySet()) {
            combined.copy(markovs_map.get(p));
        } // FOR
    
        return (combined);
    }

    /**
     * Combine multiple MarkovGraphsContainer files into a single file
     * @param markovs
     * @param output_path
     */
    public static void combine(Map<Integer, File> markovs, String output_path, Database catalog_db) {
        // Sort the list of partitions so we always iterate over them in the same order
        SortedSet<Integer> sorted = new TreeSet<Integer>(markovs.keySet());
        
        // We want all the procedures
        Set<Procedure> procedures = (Set<Procedure>)CollectionUtil.addAll(new HashSet<Procedure>(), catalog_db.getProcedures());
        
        File file = new File(output_path);
        try {
            FileOutputStream out = new FileOutputStream(file);
            
            // First construct an index that allows us to quickly find the partitions that we want
            JSONStringer stringer = (JSONStringer)(new JSONStringer().object());
            int offset = 1;
            for (Integer partition : sorted) {
                stringer.key(Integer.toString(partition)).value(offset++);
            } // FOR
            out.write((stringer.endObject().toString() + "\n").getBytes());
            
            // Now Loop through each file individually so that we only have to load one into memory at a time
            for (Integer partition : sorted) {
                File in = markovs.get(partition);
                try {
                    JSONObject json_object = new JSONObject(FileUtil.readFile(in));
                    MarkovGraphsContainer markov = MarkovGraphContainersUtil.createMarkovGraphsContainer(json_object, procedures, catalog_db);
                    markov.load(in.getAbsolutePath(), catalog_db);
                    
                    stringer = (JSONStringer)new JSONStringer().object();
                    stringer.key(partition.toString()).object();
                    markov.toJSON(stringer);
                    stringer.endObject().endObject();
                    out.write((stringer.toString() + "\n").getBytes());
                } catch (Exception ex) {
                    throw new Exception(String.format("Failed to copy MarkovGraphsContainer for partition %d from '%s'", partition, in), ex);
                }
            } // FOR
            out.close();
        } catch (Exception ex) {
            LOG.error("Failed to combine multiple MarkovGraphsContainers into file '" + output_path + "'", ex);
            throw new RuntimeException(ex);
        }
        LOG.info(String.format("Combined %d MarkovGraphsContainers into file '%s'", markovs.size(), output_path));
    }

    /**
     * 
     * @param json_object
     * @param procedures
     * @param catalog_db
     * @return
     * @throws JSONException
     */
    public static MarkovGraphsContainer createMarkovGraphsContainer(JSONObject json_object, Collection<Procedure> procedures, Database catalog_db) throws JSONException {
        // We should be able to get the classname of the container from JSON
        String className = MarkovGraphsContainer.class.getCanonicalName();
        if (json_object.has(MarkovGraphsContainer.Members.CLASSNAME.name())) {
            className = json_object.getString(MarkovGraphsContainer.Members.CLASSNAME.name());    
        }
        MarkovGraphsContainer markovs = ClassUtil.newInstance(className, new Object[]{procedures},
                                                                         new Class<?>[]{Collection.class}); 
        assert(markovs != null);
        if (debug.get()) LOG.debug(String.format("Instantiated new % object", className));
        markovs.fromJSON(json_object, catalog_db);
        return (markovs);
    }

    // ----------------------------------------------------------------------------
    // SAVE TO FILE
    // ----------------------------------------------------------------------------
    
    /**
     * For the given MarkovGraphContainer, serialize them out to a file
     * @param markovs
     * @param output_path
     * @throws Exception
     */
    public static void save(Map<Integer, ? extends MarkovGraphsContainer> markovs, String output_path) {
        final String className = MarkovGraphsContainer.class.getSimpleName();
        LOG.info("Writing out graphs of " + className + " to '" + output_path + "'");
        
        // Sort the list of partitions so we always iterate over them in the same order
        SortedSet<Integer> sorted = new TreeSet<Integer>(markovs.keySet());
        
        File file = new File(output_path);
        try {
            FileOutputStream out = new FileOutputStream(file);
            
            // First construct an index that allows us to quickly find the partitions that we want
            JSONStringer stringer = (JSONStringer)(new JSONStringer().object());
            int offset = 1;
            for (Integer partition : sorted) {
                stringer.key(Integer.toString(partition)).value(offset++);
            } // FOR
            out.write((stringer.endObject().toString() + "\n").getBytes());
            
            // Now roll through each id and create a single JSONObject on each line
            for (Integer partition : sorted) {
                MarkovGraphsContainer markov = markovs.get(partition);
                assert(markov != null) : "Null MarkovGraphsContainer for partition #" + partition;
                
                stringer = (JSONStringer)new JSONStringer().object();
                stringer.key(partition.toString()).object();
                markov.toJSON(stringer);
                stringer.endObject().endObject();
                out.write((stringer.toString() + "\n").getBytes());
            } // FOR
            out.close();
        } catch (Exception ex) {
            LOG.error("Failed to serialize the " + className + " file '" + output_path + "'", ex);
            throw new RuntimeException(ex);
        }
        if (debug.get()) LOG.debug(className + " objects were written out to '" + output_path + "'");
    }
    
    // ----------------------------------------------------------------------------
    // LOAD METHODS
    // ----------------------------------------------------------------------------
    
    public static Map<Integer, MarkovGraphsContainer> loadIds(Database catalog_db, String input_path, Collection<Integer> ids) throws Exception {
        return (MarkovGraphContainersUtil.load(catalog_db, input_path, null, ids));
    }

    public static Map<Integer, MarkovGraphsContainer> loadProcedures(Database catalog_db, String input_path, Collection<Procedure> procedures) throws Exception {
        return (MarkovGraphContainersUtil.load(catalog_db, input_path, procedures, null));
    }

    /**
     * 
     * @param catalog_db
     * @param input_path
     * @param ids
     * @return
     * @throws Exception
     */
    public static Map<Integer, MarkovGraphsContainer> load(final Database catalog_db, String input_path, Collection<Procedure> procedures, Collection<Integer> ids) throws Exception {
        final Map<Integer, MarkovGraphsContainer> ret = new HashMap<Integer, MarkovGraphsContainer>();
        final File file = new File(input_path);
        LOG.info(String.format("Loading in MarkovGraphContainers from '%s' [procedures=%s, ids=%s]",
                               file.getName(), (procedures == null ? "*ALL*" : CatalogUtil.debug(procedures)), (ids == null ? "*ALL*" : ids)));
        
        try {
            // File Format: One PartitionId per line, each with its own MarkovGraphsContainer 
            BufferedReader in = FileUtil.getReader(file);
            
            // Line# -> Partition#
            final Map<Integer, Integer> line_xref = new HashMap<Integer, Integer>();
            
            int line_ctr = 0;
            while (in.ready()) {
                final String line = in.readLine();
                
                // If this is the first line, then it is our index
                if (line_ctr == 0) {
                    // Construct our line->partition mapping
                    JSONObject json_object = new JSONObject(line);
                    for (String key : CollectionUtil.iterable(json_object.keys())) {
                        Integer partition = Integer.valueOf(key);
                        
                        // We want the MarkovGraphContainer pointed to by this line if
                        // (1) This partition is the same as our GLOBAL_MARKOV_CONTAINER_ID, which means that
                        //     there isn't going to be partition-specific graphs. There should only be one entry
                        //     in this file and we're always going to want to load it
                        // (2) They didn't pass us any ids, so we'll take everything we see
                        // (3) They did pass us ids, so check whether its included in the set
                        if (partition.equals(MarkovUtil.GLOBAL_MARKOV_CONTAINER_ID) || ids == null || ids.contains(partition)) {
                            Integer offset = json_object.getInt(key);
                            line_xref.put(offset, partition);
                        }
                    } // FOR
                    if (debug.get()) LOG.debug(String.format("Loading %d MarkovGraphsContainers", line_xref.size()));
                    
                // Otherwise check whether this is a line number that we care about
                } else if (line_xref.containsKey(Integer.valueOf(line_ctr))) {
                    Integer partition = line_xref.remove(Integer.valueOf(line_ctr));
                    JSONObject json_object = new JSONObject(line).getJSONObject(partition.toString());
                    MarkovGraphsContainer markovs = createMarkovGraphsContainer(json_object, procedures, catalog_db);
                    if (debug.get()) LOG.debug(String.format("Storing %s for partition %d", markovs.getClass().getSimpleName(), partition));
                    ret.put(partition, markovs);        
                    if (line_xref.isEmpty()) break;
                }
                line_ctr++;
            } // WHILE
            if (line_ctr == 0) throw new IOException("The MarkovGraphsContainer file '" + input_path + "' is empty");
            
        } catch (Exception ex) {
            LOG.error("Failed to deserialize the MarkovGraphsContainer from file '" + input_path + "'", ex);
            throw new IOException(ex);
        }
        if (debug.get()) LOG.debug("The loading of the MarkovGraphsContainer is complete");
        return (ret);
    }

    // ----------------------------------------------------------------------------
    // UTILITY METHODS
    // ----------------------------------------------------------------------------
    
    /**
     * Utility method to calculate the probabilities at all of the MarkovGraphsContainers
     * @param markovs
     */
    public static void calculateProbabilities(Map<Integer, ? extends MarkovGraphsContainer> markovs) {
        if (debug.get()) LOG.debug(String.format("Calculating probabilities for %d ids", markovs.size()));
        for (MarkovGraphsContainer m : markovs.values()) {
            m.calculateProbabilities();
        }
        return;
    }

    /**
     * Utility method
     * @param markovs
     * @param hasher
     */
    public static void setHasher(Map<Integer, ? extends MarkovGraphsContainer> markovs, AbstractHasher hasher) {
        if (debug.get()) LOG.debug(String.format("Setting hasher for for %d ids", markovs.size()));
        for (MarkovGraphsContainer m : markovs.values()) {
            m.setHasher(hasher);
        }
        return;
    }

}
