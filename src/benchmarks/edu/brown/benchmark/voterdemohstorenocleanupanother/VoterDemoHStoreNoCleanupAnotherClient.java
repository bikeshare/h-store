/***************************************************************************
 *  Copyright (C) 2012 by H-Store Project                                  *
 *  Brown University                                                       *
 *  Massachusetts Institute of Technology                                  *
 *  Yale University                                                        *
 *                                                                         *
 *  Original By: VoltDB Inc.											   *
 *  Ported By:  Justin A. DeBrabant (http://www.cs.brown.edu/~debrabant/)  *								   
 *                                                                         *
 *                                                                         *
 *  Permission is hereby granted, free of charge, to any person obtaining  *
 *  a copy of this software and associated documentation files (the        *
 *  "Software"), to deal in the Software without restriction, including    *
 *  without limitation the rights to use, copy, modify, merge, publish,    *
 *  distribute, sublicense, and/or sell copies of the Software, and to     *
 *  permit persons to whom the Software is furnished to do so, subject to  *
 *  the following conditions:                                              *
 *                                                                         *
 *  The above copyright notice and this permission notice shall be         *
 *  included in all copies or substantial portions of the Software.        *
 *                                                                         *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,        *
 *  EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF     *
 *  MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. *
 *  IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR      *
 *  OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,  *
 *  ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR  *
 *  OTHER DEALINGS IN THE SOFTWARE.                                        *
 ***************************************************************************/

package edu.brown.benchmark.voterdemohstorenocleanupanother;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.log4j.Logger;
import org.voltdb.VoltTable;
import org.voltdb.VoltTableRow;
import org.voltdb.client.Client;
import org.voltdb.client.ClientResponse;
import org.voltdb.client.ProcCallException;
import org.voltdb.client.ProcedureCallback;

import weka.classifiers.meta.Vote;
import edu.brown.api.BenchmarkComponent;
import edu.brown.benchmark.voterdemohstorenocleanupanother.procedures.GenerateLeaderboard;
import edu.brown.hstore.Hstoreservice.Status;
import edu.brown.logging.LoggerUtil.LoggerBoolean;

public class VoterDemoHStoreNoCleanupAnotherClient extends BenchmarkComponent {
    private static final Logger LOG = Logger.getLogger(VoterDemoHStoreNoCleanupAnotherClient.class);
    private static final LoggerBoolean debug = new LoggerBoolean();
    private static long lastTime;
    private static int timestamp;

    // Phone number generator
    //PhoneCallGenerator switchboard;
    edu.brown.stream.VoteGenerator switchboard;
    
    private String stat_filename;
    public static long count = 0l;
    public static long fixnum = 10000l;
    
    // Flags to tell the worker threads to stop or go
    AtomicBoolean warmupComplete = new AtomicBoolean(false);
    AtomicBoolean benchmarkComplete = new AtomicBoolean(false);

    // voterdemohstorenocleanupanother benchmark state
    AtomicLong acceptedVotes = new AtomicLong(0);
    AtomicLong badContestantVotes = new AtomicLong(0);
    AtomicLong badVoteCountVotes = new AtomicLong(0);
    AtomicLong failedVotes = new AtomicLong(0);
    
    boolean genLeaderboard;
    
    final StatisticCallback stat_callback =  new StatisticCallback();

    public static void main(String args[]) {
        BenchmarkComponent.main(VoterDemoHStoreNoCleanupAnotherClient.class, args, false);
    }

    public VoterDemoHStoreNoCleanupAnotherClient(String args[]) {
        super(args);
        int numContestants = VoterDemoHStoreNoCleanupAnotherUtil.getScaledNumContestants(this.getScaleFactor());
        //this.switchboard = new PhoneCallGenerator(this.getClientId(), numContestants);
        String timeLog = new SimpleDateFormat("yyyyMMdd_HHmmss").format(Calendar.getInstance().getTime());
        stat_filename = "voterdemohstorenocleanupanother-o-" + timeLog + ".txt";
        
        String filename = "votes-o-40000.ser";
        this.switchboard = new edu.brown.stream.VoteGenerator(filename);
        //System.out.println( "filename: " + filename );
        //System.out.println( "Size: " + switchboard.size() );
        
        lastTime = System.nanoTime();
        timestamp = 0;
        genLeaderboard = false;
    }

    @Override
    public void runLoop() {
        try {
            while (true) {
                // synchronously call the "Vote" procedure
                try {
                    runOnce();
                } catch (Exception e) {
                    failedVotes.incrementAndGet();
                }

            } // WHILE
        } catch (Exception e) {
            // Client has no clean mechanism for terminating with the DB.
            e.printStackTrace();
        }
    }

    @Override
    protected boolean runOnce() throws IOException {
        // Get the next phone call
//    	if(System.nanoTime() - lastTime >= VoterDemoHStoreNoCleanupAnotherConstants.TS_DURATION)
//        {
//        	lastTime = System.nanoTime();
//        	timestamp++;
//        }
    	try {
	    	Client client = this.getClientHandle();
	    	
		        //PhoneCallGenerator.PhoneCall call = switchboard.receive();
	    	    edu.brown.stream.PhoneCallGenerator.PhoneCall call = switchboard.nextVote();
	    	    //call.debug();
		        //Callback callback = new Callback(0);
	    	    
	    	    if(call==null)
	    	        return true;
		
		        ClientResponse response;
					response = client.callProcedure(       "Vote",
					                                        call.voteId,
					                                        call.phoneNumber,
					                                        call.contestantNumber,
					                                        VoterDemoHStoreNoCleanupAnotherConstants.MAX_VOTES,
					                                        call.timestamp
					                                        //timestamp
					                                        );
				
				incrementTransactionCounter(response, 0);
		        VoltTable results[] = response.getResults();
		        
		        if(results.length > 0 && results[0].asScalarLong() == VoterDemoHStoreNoCleanupAnotherConstants.VOTE_SUCCESSFUL)
		        {
		        	response = client.callProcedure("GenerateLeaderboard");
		    		incrementTransactionCounter(response, 1);
		        }
		        
		        GetStatisticInfo();
		        
		        return true;

    	} 
		catch (ProcCallException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return false;
		}
    }
    
    private void GetStatisticInfo()
    {
        try
        {
            VoterDemoHStoreNoCleanupAnotherClient.count++;
            if( VoterDemoHStoreNoCleanupAnotherClient.count == VoterDemoHStoreNoCleanupAnotherClient.fixnum )
            {
                //System.out.println("GetStatisticInfo() 1- " + String.valueOf(VoterDemoHStoreAnotherClient.fixnum));
                //System.out.println("call GetStatisticInfo ...");
                
                Client client = this.getClientHandle();
                client.callProcedure(stat_callback, "Results");

                VoterDemoHStoreNoCleanupAnotherClient.fixnum += 10000l;
                //System.out.println("GetStatisticInfo() 2- " + String.valueOf(VoterDemoHStoreAnotherClient.fixnum));
                
            }
        }
        catch(Exception e)
        {
            System.out.println(e.getMessage());
        }
    }

    @Override
    public String[] getTransactionDisplayNames() {
        // Return an array of transaction names
        String procNames[] = new String[]{
            Vote.class.getSimpleName(),
            GenerateLeaderboard.class.getSimpleName()
        };
        return (procNames);
    }

    private class Callback implements ProcedureCallback {
    	
    	private int idx;
    	private long prevStatus;
    	
    	public Callback(int idx)
    	{
    		super();
    		this.idx = idx;
    	}
    	
    	public long getStatus()
    	{
    		return prevStatus;
    	}

        @Override
        public void clientCallback(ClientResponse clientResponse) {
            // Increment the BenchmarkComponent's internal counter on the
            // number of transactions that have been completed
            incrementTransactionCounter(clientResponse, this.idx);
            
            if(this.idx == 0)
            {
	            // Keep track of state (optional)
	            if (clientResponse.getStatus() == Status.OK) {
	                VoltTable results[] = clientResponse.getResults();
	                assert(results.length == 1);
	                long status = results[0].asScalarLong();
	                prevStatus = status;
	                if (status == VoterDemoHStoreNoCleanupAnotherConstants.VOTE_SUCCESSFUL) {
	                    acceptedVotes.incrementAndGet();
	                }
	                else if (status == VoterDemoHStoreNoCleanupAnotherConstants.ERR_INVALID_CONTESTANT) {
	                    badContestantVotes.incrementAndGet();
	                }
	                else if (status == VoterDemoHStoreNoCleanupAnotherConstants.ERR_VOTER_OVER_VOTE_LIMIT) {
	                    badVoteCountVotes.incrementAndGet();
	                }
	            }
	            else if (clientResponse.getStatus() == Status.ABORT_UNEXPECTED) {
	                if (clientResponse.getException() != null) {
	                    clientResponse.getException().printStackTrace();
	                }
	                if (debug.val && clientResponse.getStatusString() != null) {
	                    LOG.warn(clientResponse.getStatusString());
	                }
	            }
            }
        }
    } // END CLASS
    
    private class StatisticCallback implements ProcedureCallback {
        @Override
        public void clientCallback(ClientResponse clientResponse)
        {
            if (clientResponse.getStatus() == Status.OK) {
                VoltTable vt = clientResponse.getResults()[0];
                
                int row_len = vt.getRowCount();
                
                String line =  String.valueOf(VoterDemoHStoreNoCleanupAnotherClient.fixnum - 10000l) + ": ";
                for(int i=0;i<row_len; i++)
                {
                    VoltTableRow row = vt.fetchRow(i);
                    String contestant_name = row.getString(0);
                    long total_votes = row.getLong(2);
                    
                    String content = contestant_name + "-" + String.valueOf(total_votes);
                    
                    line += content + " ";
                }
                
                //System.out.println(line);
                
                try {
                    WriteToFile(line);
                } catch (IOException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
                
            }            
        }
        
        private void WriteToFile(String content) throws IOException
        {
            //System.out.println(stat_filename + " : " + content );
            PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(stat_filename, true)));
            out.println(content);
            out.close();
        }
    }
}
