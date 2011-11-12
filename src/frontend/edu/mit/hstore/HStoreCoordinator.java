package edu.mit.hstore;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.voltdb.catalog.Database;
import org.voltdb.catalog.Host;
import org.voltdb.catalog.Site;
import org.voltdb.messaging.FragmentTaskMessage;
import org.voltdb.messaging.VoltMessage;
import org.voltdb.utils.Pair;

import ca.evanjones.protorpc.NIOEventLoop;
import ca.evanjones.protorpc.ProtoRpcChannel;
import ca.evanjones.protorpc.ProtoRpcController;
import ca.evanjones.protorpc.ProtoServer;

import com.google.protobuf.ByteString;
import com.google.protobuf.RpcCallback;
import com.google.protobuf.RpcController;

import edu.brown.catalog.CatalogUtil;
import edu.brown.hstore.Hstore;
import edu.brown.hstore.Hstore.*;
import edu.brown.hstore.Hstore.TransactionWorkRequest.PartitionFragment;
import edu.brown.logging.LoggerUtil;
import edu.brown.logging.LoggerUtil.LoggerBoolean;
import edu.brown.utils.ProfileMeasurement;
import edu.brown.utils.ThreadUtil;
import edu.mit.hstore.callbacks.TransactionFinishCallback;
import edu.mit.hstore.callbacks.TransactionInitCallback;
import edu.mit.hstore.callbacks.TransactionPrepareCallback;
import edu.mit.hstore.callbacks.TransactionRedirectResponseCallback;
import edu.mit.hstore.callbacks.TransactionWorkCallback;
import edu.mit.hstore.dtxn.LocalTransaction;
import edu.mit.hstore.dtxn.RemoteTransaction;
import edu.mit.hstore.handlers.TransactionFinishHandler;
import edu.mit.hstore.handlers.TransactionInitHandler;
import edu.mit.hstore.handlers.TransactionPrepareHandler;
import edu.mit.hstore.interfaces.Shutdownable;

/**
 * 
 * @author pavlo
 */
public class HStoreCoordinator implements Shutdownable {
    public static final Logger LOG = Logger.getLogger(HStoreCoordinator.class);
    private final static LoggerBoolean debug = new LoggerBoolean(LOG.isDebugEnabled());
    private final static LoggerBoolean trace = new LoggerBoolean(LOG.isTraceEnabled());
    static {
        LoggerUtil.attachObserver(LOG, debug, trace);
    }

//    private static final DBBPool buffer_pool = new DBBPool(true, false);
//    private static final ByteString ok = ByteString.copyFrom("OK".getBytes());
    
    private final HStoreSite hstore_site;
    private final HStoreConf hstore_conf;
    private final Site catalog_site;
    private final int local_site_id;
    private final Collection<Integer> local_partitions;
    private final NIOEventLoop eventLoop = new NIOEventLoop();
    
    /** SiteId -> HStoreServer */
    private final Map<Integer, HStoreService> channels = new HashMap<Integer, HStoreService>();
    
    private final Thread listener_thread;
    private final ProtoServer listener;
    private final RemoteSiteHandler handler = new RemoteSiteHandler();
    
    private final TransactionInitHandler router_transactionInit;
    private final TransactionPrepareHandler router_transactionPrepare;
    private final TransactionFinishHandler router_transactionFinish;
    
    private final InitDispatcher initDispatcher;
    private final FinishDispatcher finishDispatcher;
    private final ForwardTxnDispatcher forwardDispatcher;    
    
    private boolean shutting_down = false;
    private Shutdownable.ShutdownState state = ShutdownState.INITIALIZED;
    

    /**
     * 
     */
    private class MessengerListener implements Runnable {
        @Override
        public void run() {
            if (hstore_conf.site.cpu_affinity)
                hstore_site.getThreadManager().registerProcessingThread();
            Throwable error = null;
            try {
                HStoreCoordinator.this.eventLoop.run();
            } catch (RuntimeException ex) {
                error = ex;
            } catch (AssertionError ex) {
                error = ex;
            } catch (Exception ex) {
                error = ex;
            }
            
            if (error != null) {
                if (hstore_site.isShuttingDown() == false) {
                    LOG.error(this.getClass().getSimpleName() + " has stopped!", error);
                }
                
                Throwable cause = null;
                if (error instanceof RuntimeException && error.getCause() != null) {
                    if (error.getCause().getMessage() != null && error.getCause().getMessage().isEmpty() == false) {
                        cause = error.getCause();
                    }
                }
                if (cause == null) cause = error;
                
                // These errors are ok if we're actually stopping...
                if (HStoreCoordinator.this.state == ShutdownState.SHUTDOWN ||
                    HStoreCoordinator.this.state == ShutdownState.PREPARE_SHUTDOWN ||
                    HStoreCoordinator.this.hstore_site.isShuttingDown()) {
                    // IGNORE
                } else {
                    LOG.fatal("Unexpected error in messenger listener thread", cause);
                    HStoreCoordinator.this.shutdownCluster(error);
                }
            }
            if (trace.get()) LOG.trace("__FILE__:__LINE__ " + "Messenger Thread for Site #" + catalog_site.getId() + " has stopped!");
        }
    }
    
    public abstract class Dispatcher<E> implements Runnable {
        final ProfileMeasurement idleTime = new ProfileMeasurement("IDLE");
        final LinkedBlockingDeque<E> queue = new LinkedBlockingDeque<E>();
        
        @Override
        public final void run() {
            if (hstore_conf.site.cpu_affinity)
                hstore_site.getThreadManager().registerProcessingThread();
            E e = null;
            while (HStoreCoordinator.this.shutting_down == false) {
                try {
                    idleTime.start();
                    e = this.queue.take();
                    idleTime.stop();
                } catch (InterruptedException ex) {
                    break;
                }
                try {
                    this.runImpl(e);
                } catch (Throwable ex) {
                    LOG.warn("Failed to process queued element " + e, ex);
                    continue;
                }
            } // WHILE
        }
        public void queue(E e) {
            this.queue.offer(e);
        }
        public abstract void runImpl(E e);
        
        
    }
    
    /**
     * 
     */
    private class ForwardTxnDispatcher extends Dispatcher<Pair<byte[], TransactionRedirectResponseCallback>> {
        @Override
        public void runImpl(Pair<byte[], TransactionRedirectResponseCallback> p) {
            HStoreCoordinator.this.hstore_site.procedureInvocation(p.getFirst(), p.getSecond());
        }
    }
    
    /**
     * 
     */
    private class InitDispatcher extends Dispatcher<Object[]> {
        @SuppressWarnings("unchecked")
        @Override
        public void runImpl(Object o[]) {
            RpcController controller = (RpcController)o[0];
            TransactionInitRequest request = (TransactionInitRequest)o[1];
            RpcCallback<TransactionInitResponse> callback = (RpcCallback<TransactionInitResponse>)o[2];
            router_transactionInit.remoteHandler(controller, request, callback);
        }
    }
    /**
     * 
     */
    private class FinishDispatcher extends Dispatcher<Object[]> {
        @SuppressWarnings("unchecked")
        @Override
        public void runImpl(Object o[]) {
            RpcController controller = (RpcController)o[0];
            TransactionFinishRequest request = (TransactionFinishRequest)o[1];
            RpcCallback<TransactionFinishResponse> callback = (RpcCallback<TransactionFinishResponse>)o[2];
            router_transactionFinish.remoteHandler(controller, request, callback);
        }
    }
    
    /**
     * Constructor
     * @param hstore_site
     */
    public HStoreCoordinator(HStoreSite hstore_site) {
        this.hstore_site = hstore_site;
        this.hstore_conf = hstore_site.getHStoreConf();
        this.catalog_site = hstore_site.getSite();
        this.local_site_id = this.catalog_site.getId();
        this.local_partitions = hstore_site.getLocalPartitionIds();
        if (debug.get()) LOG.debug("__FILE__:__LINE__ " + "Local Partitions for Site #" + hstore_site.getSiteId() + ": " + this.local_partitions);

        // This listener thread will process incoming messages
        this.listener = new ProtoServer(this.eventLoop);
        
        // Special dispatcher threads to handle forward requests
        this.initDispatcher = (hstore_conf.site.coordinator_init_thread ? new InitDispatcher() : null);
        this.finishDispatcher = (hstore_conf.site.coordinator_finish_thread ? new FinishDispatcher() : null);
        this.forwardDispatcher = (hstore_conf.site.coordinator_redirect_thread ? new ForwardTxnDispatcher() : null);

        this.router_transactionInit = new TransactionInitHandler(hstore_site, this, initDispatcher);
        this.router_transactionPrepare = new TransactionPrepareHandler(hstore_site, this);
        this.router_transactionFinish = new TransactionFinishHandler(hstore_site, this, finishDispatcher);
        
        // Wrap the listener in a daemon thread
        this.listener_thread = new Thread(new MessengerListener(), HStoreSite.getThreadName(this.hstore_site, "coord"));
        this.listener_thread.setDaemon(true);
        this.eventLoop.setExitOnSigInt(true);
    }
    
    /**
     * Start the messenger. This is a blocking call that will initialize the connections
     * and start the listener thread!
     */
    public synchronized void start() {
        assert(this.state == ShutdownState.INITIALIZED) : "Invalid MessengerState " + this.state;
        
        this.state = ShutdownState.STARTED;
        
        if (debug.get()) LOG.debug("__FILE__:__LINE__ " + "Initializing connections");
        this.initConnections();

        if (this.initDispatcher != null) {
            if (debug.get()) LOG.debug("__FILE__:__LINE__ " + "Starting InitTransaction dispatcher thread");
            Thread t = new Thread(initDispatcher, HStoreSite.getThreadName(this.hstore_site, "init"));
            t.setDaemon(true);
            t.start();
        }
        if (this.finishDispatcher != null) {
            if (debug.get()) LOG.debug("__FILE__:__LINE__ " + "Starting FinishTransaction dispatcher thread");
            Thread t = new Thread(finishDispatcher, HStoreSite.getThreadName(this.hstore_site, "finish"));
            t.setDaemon(true);
            t.start();
        }
        if (this.forwardDispatcher != null) {
            if (debug.get()) LOG.debug("__FILE__:__LINE__ " + "Starting ForwardTxn dispatcher thread");
            Thread t = new Thread(forwardDispatcher, HStoreSite.getThreadName(this.hstore_site, "frwd"));
            t.setDaemon(true);
            t.start();
        }
        
        if (debug.get()) LOG.debug("__FILE__:__LINE__ " + "Starting listener thread");
        this.listener_thread.start();
    }

    /**
     * Returns true if the messenger has started
     * @return
     */
    public boolean isStarted() {
        return (this.state == ShutdownState.STARTED ||
                this.state == ShutdownState.PREPARE_SHUTDOWN);
    }
    
    /**
     * Internal call for testing to hide errors
     */
    @Override
    public void prepareShutdown() {
        if (this.state != ShutdownState.PREPARE_SHUTDOWN) {
            assert(this.state == ShutdownState.STARTED) : "Invalid MessengerState " + this.state;
            this.state = ShutdownState.PREPARE_SHUTDOWN;
        }
    }
    
    /**
     * Stop this messenger. This kills the ProtoRpc event loop
     */
    @Override
    public synchronized void shutdown() {
        assert(this.state == ShutdownState.STARTED || this.state == ShutdownState.PREPARE_SHUTDOWN) : "Invalid MessengerState " + this.state;
        
        this.state = ShutdownState.SHUTDOWN;
        
        try {
            if (trace.get()) LOG.trace("__FILE__:__LINE__ " + "Stopping eventLoop for Site #" + this.getLocalSiteId());
            this.eventLoop.exitLoop();

            if (trace.get()) LOG.trace("__FILE__:__LINE__ " + "Stopping listener thread for Site #" + this.getLocalSiteId());
            this.listener_thread.interrupt();
            
            if (trace.get()) LOG.trace("__FILE__:__LINE__ " + "Joining on listener thread for Site #" + this.getLocalSiteId());
            this.listener_thread.join();
        } catch (InterruptedException ex) {
            // IGNORE
        } catch (Throwable ex) {
            LOG.error("Unexpected error when trying to stop messenger for Site #" + this.getLocalSiteId(), ex);
        } finally {
            if (trace.get()) LOG.trace("__FILE__:__LINE__ " + "Closing listener socket for Site #" + this.getLocalSiteId());
            this.listener.close();
        }
        assert(this.isShuttingDown());
    }
    
    /**
     * Returns true if the messenger has stopped
     * @return
     */
    @Override
    public boolean isShuttingDown() {
        return (this.state == ShutdownState.SHUTDOWN);
    }
    
    protected int getLocalSiteId() {
        return (this.local_site_id);
    }
    protected int getLocalMessengerPort() {
        return (this.hstore_site.getSite().getMessenger_port());
    }
    protected final Thread getListenerThread() {
        return (this.listener_thread);
    }
    
    public HStoreService getChannel(int site_id) {
        return (this.channels.get(site_id));
    }
    public HStoreService getHandler() {
        return (this.handler);
    }
    
//    private int getNumLocalPartitions(Collection<Integer> partitions) {
//        int ctr = 0;
//        int size = partitions.size();
//        for (Integer p : this.local_partitions) {
//            if (partitions.contains(p)) {
//                ctr++;
//                if (size == ctr) break;
//            }
//        } // FOR
//        return (ctr);
//    }
//    
//    private VoltTable copyVoltTable(VoltTable vt) throws Exception {
//        FastSerializer fs = new FastSerializer(buffer_pool);
//        fs.writeObject(vt);
//        BBContainer bc = fs.getBBContainer();
//        assert(bc.b.hasArray());
//        ByteString bs = ByteString.copyFrom(bc.b);
//        
//        FastDeserializer fds = new FastDeserializer(bs.asReadOnlyByteBuffer());
//        VoltTable copy = fds.readObject(VoltTable.class);
//        return (copy);
//    }
    
    /**
     * Initialize all the network connections to remote 
     */
    private void initConnections() {
        Database catalog_db = CatalogUtil.getDatabase(this.catalog_site);
        
        // Find all the destinations we need to connect to
        if (debug.get()) LOG.debug("__FILE__:__LINE__ " + "Configuring outbound network connections for Site #" + this.catalog_site.getId());
        Map<Host, Set<Site>> host_partitions = CatalogUtil.getSitesPerHost(catalog_db);
        Integer local_port = this.catalog_site.getMessenger_port();
        
        ArrayList<Integer> site_ids = new ArrayList<Integer>();
        ArrayList<InetSocketAddress> destinations = new ArrayList<InetSocketAddress>();
        for (Entry<Host, Set<Site>> e : host_partitions.entrySet()) {
            String host = e.getKey().getIpaddr();
            for (Site catalog_site : e.getValue()) {
                int site_id = catalog_site.getId();
                int port = catalog_site.getMessenger_port();
                if (site_id != this.catalog_site.getId()) {
                    if (debug.get()) LOG.debug("__FILE__:__LINE__ " + "Creating RpcChannel to " + host + ":" + port + " for site #" + site_id);
                    destinations.add(new InetSocketAddress(host, port));
                    site_ids.add(site_id);
                } // FOR
            } // FOR 
        } // FOR
        
        // Initialize inbound channel
        assert(local_port != null);
        if (debug.get()) LOG.debug("__FILE__:__LINE__ " + "Binding listener to port " + local_port + " for Site #" + this.catalog_site.getId());
        this.listener.register(this.handler);
        this.listener.bind(local_port);

        // Make the outbound connections
        if (destinations.isEmpty()) {
            if (debug.get()) LOG.debug("__FILE__:__LINE__ " + "There are no remote sites so we are skipping creating connections");
        } else {
            if (debug.get()) LOG.debug("__FILE__:__LINE__ " + "Connecting to " + destinations.size() + " remote site messengers");
            ProtoRpcChannel[] channels = null;
            try {
                channels = ProtoRpcChannel.connectParallel(this.eventLoop, destinations.toArray(new InetSocketAddress[]{}), 15000);
            } catch (RuntimeException ex) {
                LOG.warn("Failed to connect to remote sites. Going to try again...");
                // Try again???
                try {
                    channels = ProtoRpcChannel.connectParallel(this.eventLoop, destinations.toArray(new InetSocketAddress[]{}));
                } catch (Exception ex2) {
                    LOG.fatal("Site #" + this.getLocalSiteId() + " failed to connect to remote sites");
                    this.listener.close();
                    throw ex;    
                }
            }
            assert channels.length == site_ids.size();
            for (int i = 0; i < channels.length; i++) {
                this.channels.put(site_ids.get(i), HStoreService.newStub(channels[i]));
            } // FOR

            
            if (debug.get()) LOG.debug("__FILE__:__LINE__ " + "Site #" + this.getLocalSiteId() + " is fully connected to all sites");
        }
    }
    
    // ----------------------------------------------------------------------------
    // MESSAGE ROUTERS
    // ----------------------------------------------------------------------------
    
    // TransactionFinish
    
    // Shutdown
//    private final MessageRouter<ShutdownRequest, ShutdownResponse> router_shutdown = new MessageRouter<ShutdownRequest, ShutdownResponse>() {
//        protected void sendLocal(long txn_id, ShutdownRequest request, Collection<Integer> partitions, RpcCallback<ShutdownResponse> callback) {
//            
//        }
//        protected void sendRemote(HStoreService channel, ProtoRpcController controller, ShutdownRequest request, RpcCallback<ShutdownResponse> callback) {
//            channel.shutdown(controller, request, callback);
//        }
//        protected ProtoRpcController getProtoRpcController(LocalTransaction ts, int site_id) {
//            return new ProtoRpcController();
//        }
//    };

    
    // ----------------------------------------------------------------------------
    // HSTORE RPC SERVICE METHODS
    // ----------------------------------------------------------------------------
    
    /**
     * We want to make this a private inner class so that we do not expose
     * the RPC methods to other parts of the code.
     */
    private class RemoteSiteHandler extends HStoreService {
    
        @Override
        public void transactionInit(RpcController controller, TransactionInitRequest request,
                RpcCallback<TransactionInitResponse> callback) {
             router_transactionInit.remoteQueue(controller, request, callback);
        }
        
        @Override
        public void transactionWork(RpcController controller, TransactionWorkRequest request,
                RpcCallback<TransactionWorkResponse> done) {
            assert(request.hasTransactionId()) : "Got Hstore." + request.getClass().getSimpleName() + " without a txn id!";
            long txn_id = request.getTransactionId();
            if (debug.get())
                LOG.debug("__FILE__:__LINE__ " + String.format("Got %s for txn #%d [partitionFragments=%d]",
                                       request.getClass().getSimpleName(), txn_id, request.getFragmentsCount()));
            
            // This is work from a transaction executing at another node
            // Any other message can just be sent along to the ExecutionSite without sending
            // back anything right away. The ExecutionSite will use our wrapped callback handle
            // to send back whatever response it needs to, but we won't actually send it
            // until we get back results from all of the partitions
            // TODO: The base information of a set of FragmentTaskMessages should be moved into
            // the message wrapper (e.g., base partition, client handle)
            RemoteTransaction ts = hstore_site.getTransaction(txn_id);
            FragmentTaskMessage ftask = null;
            boolean first = true;
            for (PartitionFragment partition_task : request.getFragmentsList()) {
                // Decode the inner VoltMessage
                try {
                    ftask = (FragmentTaskMessage)VoltMessage.createMessageFromBuffer(partition_task.getWork().asReadOnlyByteBuffer(), false);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                assert(txn_id == ftask.getTxnId()) :
                    String.format("%s is for txn #%d but the %s has txn #%d",
                                  request.getClass().getSimpleName(), txn_id,
                                  ftask.getClass().getSimpleName(), ftask.getTxnId());

                // If this is the first time we've been here, then we need to create a RemoteTransaction handle
                if (ts == null) {
                    ts = hstore_site.createRemoteTransaction(txn_id, ftask);
                }
                
                // Always initialize the TransactionWorkCallback for the first callback 
                if (first) {
                    TransactionWorkCallback callback = ts.getFragmentTaskCallback();
                    if (callback.isInitialized()) callback.finish();
                    callback.init(txn_id, request.getFragmentsCount(), done);
                }
                
                hstore_site.transactionWork(ts, ftask);
                first = false;
            } // FOR
            assert(ts != null);
            assert(txn_id == ts.getTransactionId()) :
                String.format("Mismatched %s - Expected[%d] != Actual[%s]", ts, txn_id, ts.getTransactionId());
            
            // We don't need to send back a response right here.
            // TransactionWorkCallback will wait until it has results from all of the partitions 
            // the tasks were sent to and then send back everything in a single response message
        }
        
        @Override
        public void transactionPrepare(RpcController controller, TransactionPrepareRequest request,
                RpcCallback<TransactionPrepareResponse> callback) {
            router_transactionPrepare.remoteQueue(controller, request, callback);
        }
        
        @Override
        public void transactionFinish(RpcController controller, TransactionFinishRequest request,
                RpcCallback<TransactionFinishResponse> callback) {
            router_transactionFinish.remoteQueue(controller, request, callback);
        }
        
        @Override
        public void transactionRedirect(RpcController controller, TransactionRedirectRequest request,
                RpcCallback<TransactionRedirectResponse> done) {
            // We need to create a wrapper callback so that we can get the output that
            // HStoreSite wants to send to the client and forward 
            // it back to whomever told us about this txn
            if (debug.get()) 
                LOG.debug("__FILE__:__LINE__ " + String.format("Recieved redirected transaction request from HStoreSite %s", HStoreSite.formatSiteName(request.getSenderId())));
            byte serializedRequest[] = request.getWork().toByteArray(); // XXX Copy!
            TransactionRedirectResponseCallback callback = null;
            try {
                callback = (TransactionRedirectResponseCallback)HStoreObjectPools.CALLBACKS_TXN_REDIRECTRESPONSE.borrowObject();
                callback.init(local_site_id, request.getSenderId(), done);
            } catch (Exception ex) {
                throw new RuntimeException("Failed to get ForwardTxnResponseCallback", ex);
            }
            
            if (HStoreCoordinator.this.forwardDispatcher != null) {
                HStoreCoordinator.this.forwardDispatcher.queue.add(Pair.of(serializedRequest, callback));
            } else {
                hstore_site.procedureInvocation(serializedRequest, callback);
            }
        }
        
        @Override
        public void shutdown(RpcController controller, ShutdownRequest request,
                RpcCallback<ShutdownResponse> done) {
            LOG.info(String.format("Got shutdown request from HStoreSite %s", HStoreSite.formatSiteName(request.getSenderId())));
            
            HStoreCoordinator.this.shutting_down = true;
            
            // Tell the HStoreSite to shutdown
            hstore_site.shutdown();
            
            // Then send back the acknowledgment
            Hstore.ShutdownResponse response = Hstore.ShutdownResponse.newBuilder()
                                                   .setSenderId(local_site_id)
                                                   .build();
            // Send this now!
            done.run(response);
            LOG.info(String.format("Shutting down %s [status=%d]", hstore_site.getSiteName(), request.getExitStatus()));
            if (debug.get())
                LOG.debug("__FILE__:__LINE__ " + String.format("ForwardDispatcher Queue Idle Time: %.2fms",
                                       forwardDispatcher.idleTime.getTotalThinkTimeMS()));
            ThreadUtil.sleep(1000); // HACK
            LogManager.shutdown();
            System.exit(request.getExitStatus());
            
        }
    } // END CLASS
    
    
    // ----------------------------------------------------------------------------
    // TRANSACTION METHODS
    // ----------------------------------------------------------------------------

    /**
     * 
     * @param ts
     * @param callback
     */
    public void transactionInit(LocalTransaction ts, TransactionInitCallback callback) {
        Hstore.TransactionInitRequest request = Hstore.TransactionInitRequest.newBuilder()
                                                         .setTransactionId(ts.getTransactionId())
                                                         .addAllPartitions(ts.getPredictTouchedPartitions())
                                                         .build();
        this.router_transactionInit.sendMessages(ts, request, callback, request.getPartitionsList());
    }
    
    /**
     * 
     * @param builders
     * @param callback
     */
    public void transactionWork(LocalTransaction ts, Map<Integer, Hstore.TransactionWorkRequest.Builder> builders, RpcCallback<Hstore.TransactionWorkResponse> callback) {
        for (Entry<Integer, Hstore.TransactionWorkRequest.Builder> e : builders.entrySet()) {
            int site_id = e.getKey().intValue();
            assert(e.getValue().getFragmentsCount() > 0);
            Hstore.TransactionWorkRequest request = e.getValue().build();
            
            // We should never get work for our local partitions
            assert(site_id != this.local_site_id);
            assert(ts.getTransactionId() == request.getTransactionId()) :
                String.format("%s is for txn #%d but the %s has txn #%d",
                              ts.getClass().getSimpleName(), ts.getTransactionId(),
                              request.getClass().getSimpleName(), request.getTransactionId());
            
            this.channels.get(site_id).transactionWork(ts.getTransactionWorkController(site_id), request, callback);
        } // FOR
    }
    
    /**
     * Notify the given partitions that this transaction is finished with them
     * This can also be used for the "early prepare" optimization.
     * @param ts
     * @param callback
     * @param partitions
     */
    public void transactionPrepare(LocalTransaction ts, TransactionPrepareCallback callback, Collection<Integer> partitions) {
        if (debug.get())
            LOG.debug("__FILE__:__LINE__ " + String.format("Notifying partitions %s that %s is preparing to commit", partitions, ts));
        
        Hstore.TransactionPrepareRequest request = Hstore.TransactionPrepareRequest.newBuilder()
                                                        .setTransactionId(ts.getTransactionId())
                                                        .addAllPartitions(partitions)
                                                        .build();
        this.router_transactionPrepare.sendMessages(ts, request, callback, partitions);
        
    }

    /**
     * Notify all remote HStoreSites that the distributed transaction is done with data
     * at the given partitions and that they need to commit/abort the results.
     * IMPORTANT: Any data that you need from the LocalTransaction handle should be taken
     * care of before this is invoked, because it may clean-up that object before it returns
     * @param ts
     * @param status
     * @param callback
     */
    public void transactionFinish(LocalTransaction ts, Hstore.Status status, TransactionFinishCallback callback) {
        Collection<Integer> partitions = ts.getPredictTouchedPartitions();
        if (debug.get())
            LOG.debug("__FILE__:__LINE__ " + String.format("Notifying partitions %s that %s is finished [status=%s]", partitions, ts, status));
        
        Hstore.TransactionFinishRequest request = Hstore.TransactionFinishRequest.newBuilder()
                                                        .setTransactionId(ts.getTransactionId())
                                                        .setStatus(status)
                                                        .addAllPartitions(partitions)
                                                        .build();
        this.router_transactionFinish.sendMessages(ts, request, callback, partitions);
        
        // HACK: At this point we can tell the local partitions that the txn is done
        // through its callback. This is just so that we don't have to serialize a
        // TransactionFinishResponse message
//        for (Integer p : partitions) {
//            if (this.local_partitions.contains(p)) {
//                if (trace.get())
//                    LOG.trace("__FILE__:__LINE__ " + String.format("Notifying %s that %s is finished at partition %d",
//                                            callback.getClass().getSimpleName(), ts, p));
//                callback.decrementCounter(1);
//            }
//        } // FOR
    }
    
    /**
     * Forward a StoredProcedureInvocation request to a remote site for execution
     * @param serializedRequest
     * @param callback
     * @param partition
     */
    public void transactionRedirect(byte[] serializedRequest, RpcCallback<Hstore.TransactionRedirectResponse> callback, int partition) {
        int dest_site_id = hstore_site.getSiteIdForPartitionId(partition);
        if (debug.get()) LOG.debug("__FILE__:__LINE__ " + "Redirecting transaction request to partition #" + partition + " on " + HStoreSite.formatSiteName(dest_site_id));
        ByteString bs = ByteString.copyFrom(serializedRequest);
        Hstore.TransactionRedirectRequest mr = Hstore.TransactionRedirectRequest.newBuilder()
                                        .setSenderId(this.local_site_id)
                                        .setWork(bs)
                                        .build();
        this.channels.get(dest_site_id).transactionRedirect(new ProtoRpcController(), mr, callback);
    }
    
    // ----------------------------------------------------------------------------
    // SHUTDOWN METHODS
    // ----------------------------------------------------------------------------
    
    /**
     * Take down the cluster. If the blocking flag is true, then this call will never return
     * @param ex
     * @param blocking
     */
    public void shutdownCluster(final Throwable ex, final boolean blocking) {
        if (debug.get())
            LOG.debug("__FILE__:__LINE__ " + String.format("Invoking shutdown protocol [blocking=%s, ex=%s]", blocking, ex));
        if (blocking) {
            this.shutdownCluster(null);
        } else {
            // Make this a thread so that we don't block and can continue cleaning up other things
            Thread shutdownThread = new Thread() {
                @Override
                public void run() {
                    HStoreCoordinator.this.shutdownCluster(ex); // Never returns!
                }
            };
            shutdownThread.setDaemon(true);
            shutdownThread.start();
        }
        return;
    }
    
    /**
     * Tell all of the other sites to shutdown and then knock ourselves out...
     * This is will never return
     */
    public void shutdownCluster() {
        this.shutdownCluster(null);
    }
    
    /**
     * Shutdown the cluster. If the given Exception is not null, then all the nodes will
     * exit with a non-zero status. This is will never return
     * @param ex
     */
    public synchronized void shutdownCluster(Throwable ex) {
        final int num_sites = this.channels.size();
        if (this.shutting_down) return;
        this.shutting_down = true;
        this.hstore_site.prepareShutdown();
        LOG.info("Shutting down cluster" + (ex != null ? ": " + ex.getMessage() : ""));

        final int exit_status = (ex == null ? 0 : 1);
        final CountDownLatch latch = new CountDownLatch(num_sites);
        
        if (num_sites > 0) {
            RpcCallback<Hstore.ShutdownResponse> callback = new RpcCallback<Hstore.ShutdownResponse>() {
                private final Set<Integer> siteids = new HashSet<Integer>(); 
                
                @Override
                public void run(Hstore.ShutdownResponse parameter) {
                    int siteid = parameter.getSenderId();
                    assert(this.siteids.contains(siteid) == false) : "Duplicate response from " + hstore_site.getSiteName();
                    this.siteids.add(siteid);
                    if (trace.get()) LOG.trace("__FILE__:__LINE__ " + "Received " + this.siteids.size() + "/" + num_sites + " shutdown acknowledgements");
                    latch.countDown();
                }
            };
            
            if (debug.get()) LOG.debug("__FILE__:__LINE__ " + "Sending shutdown request to " + num_sites + " remote sites");
            for (Entry<Integer, HStoreService> e: this.channels.entrySet()) {
                Hstore.ShutdownRequest sm = Hstore.ShutdownRequest.newBuilder()
                                                .setSenderId(catalog_site.getId())
                                                .setExitStatus(exit_status)
                                                .build();
                e.getValue().shutdown(new ProtoRpcController(), sm, callback);
                if (trace.get()) LOG.trace("__FILE__:__LINE__ " + "Sent SHUTDOWN to " + HStoreSite.formatSiteName(e.getKey()));
            } // FOR
        }
        
        // Tell ourselves to shutdown while we wait
        if (debug.get()) LOG.debug("__FILE__:__LINE__ " + "Telling local site to shutdown");
        this.hstore_site.shutdown();
        
        // Block until the latch releases us
        if (num_sites > 0) {
            LOG.info(String.format("Waiting for %d sites to finish shutting down", latch.getCount()));
            try {
                latch.await(5, TimeUnit.SECONDS);
            } catch (Exception ex2) {
                // IGNORE!
            }
        }
        LOG.info(String.format("Shutting down [site=%d, status=%d]", catalog_site.getId(), exit_status));
        LogManager.shutdown();
        System.exit(exit_status);
    }


    // ----------------------------------------------------------------------------
    // UTILITY METHODS
    // ----------------------------------------------------------------------------
    
    /**
     * Returns an HStoreService handle that is connected to the given site
     * @param catalog_site
     * @return
     */
    protected static HStoreService getHStoreService(Site catalog_site) {
        NIOEventLoop eventLoop = new NIOEventLoop();
        InetSocketAddress addresses[] = new InetSocketAddress[] {
            new InetSocketAddress(catalog_site.getHost().getIpaddr(), catalog_site.getMessenger_port()) 
        };
        ProtoRpcChannel[] channels = null;
        try {
            channels = ProtoRpcChannel.connectParallel(eventLoop, addresses);
        } catch (Exception ex) {
            
        }
        HStoreService channel = HStoreService.newStub(channels[0]);
        return (channel);
    }
}