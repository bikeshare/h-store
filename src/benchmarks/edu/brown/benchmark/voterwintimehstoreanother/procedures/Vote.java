/* This file is part of VoltDB.
 * Copyright (C) 2008-2012 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

//
// Accepts a vote, enforcing business logic: make sure the vote is for a valid
// contestant and that the voterwintimehstore (phone number of the caller) is not above the
// number of allowed votes.
//

package edu.brown.benchmark.voterwintimehstoreanother.procedures;

import org.voltdb.ProcInfo;
import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;
import org.voltdb.types.TimestampType;

import edu.brown.benchmark.voterwintimehstoreanother.VoterWinTimeHStoreAnotherConstants;

@ProcInfo (
    partitionInfo = "votes.phone_number:1",
    singlePartition = true
)
public class Vote extends VoltProcedure {

	
    // Checks if the vote is for a valid contestant
    public final SQLStmt checkContestantStmt = new SQLStmt(
	   "SELECT contestant_number FROM contestants WHERE contestant_number = ?;"
    );
	
    // Checks if the voterwintimehstore has exceeded their allowed number of votes
    public final SQLStmt checkVoterWinTimeHStoreAnotherStmt = new SQLStmt(
		"SELECT num_votes FROM v_votes_by_phone_number WHERE phone_number = ?;"
    );
	
    // Checks an area code to retrieve the corresponding state
    public final SQLStmt checkStateStmt = new SQLStmt(
		"SELECT state FROM area_code_state WHERE area_code = ?;"
    );
	
    // Records a vote
    public final SQLStmt insertVoteStmt = new SQLStmt(
		"INSERT INTO votes (vote_id, phone_number, state, contestant_number, ts) VALUES (?, ?, ?, ?, ?);"
    );
    
    // Put the vote into the staging window
    public final SQLStmt insertVoteStagingStmt = new SQLStmt(
		"INSERT INTO w_staging (vote_id, phone_number, state, contestant_number, ts) VALUES (?, ?, ?, ?, ?);"
    );
    
 // Find the cutoff vote
    public final SQLStmt checkStagingTimestamp = new SQLStmt(
    	"SELECT MAX(ts) FROM w_staging;"	
    );
    
    public final SQLStmt checkWindowTimestamp = new SQLStmt(
    	"SELECT MIN(ts) FROM w_rows;"	
    );
    
 // Find the cutoff vote
    public final SQLStmt deleteCutoffVoteStmt = new SQLStmt(
		"DELETE FROM w_rows WHERE ts < ?;"
    );
    
    // Put the staging votes into the window
    public final SQLStmt insertVoteWindowStmt = new SQLStmt(
		"INSERT INTO w_rows (vote_id, phone_number, state, contestant_number, ts) SELECT * FROM w_staging;"
    );
    
    public final SQLStmt deleteMostRecentVote = new SQLStmt(
		"DELETE FROM w_rows WHERE ts = ?;"
    );
    /**
 // Pull aggregate from window
    public final SQLStmt deleteLeaderBoardStmt = new SQLStmt(
		"DELETE FROM leaderboard;"
    );
    
    // Pull aggregate from window
    public final SQLStmt updateLeaderBoardStmt = new SQLStmt(
		"INSERT INTO leaderboard (contestant_number, numvotes) SELECT contestant_number, count(*) FROM w_rows GROUP BY contestant_number;"
    );
    */
 // Clear the staging window
    public final SQLStmt deleteStagingStmt = new SQLStmt(
		"DELETE FROM w_staging;"
    );
    
 // Put the vote into the staging window
   // public final SQLStmt UpdateLeaderBoardStmt = new SQLStmt(
	//	"INSERT INTO votes (vote_id, phone_number, state, contestant_number, created) VALUES (?, ?, ?, ?, ?);"
    //);
	
    public long run(long voteId, long phoneNumber, int contestantNumber, long maxVotesPerPhoneNumber, int currentTimestamp) {
		
        // Queue up validation statements
        voltQueueSQL(checkContestantStmt, contestantNumber);
        voltQueueSQL(checkVoterWinTimeHStoreAnotherStmt, phoneNumber);
        voltQueueSQL(checkStateStmt, (short)(phoneNumber / 10000000l));
        //voltQueueSQL(checkStagingTimestamp);
        voltQueueSQL(checkWindowTimestamp);
        VoltTable validation[] = voltExecuteSQL();
		
        if (validation[0].getRowCount() == 0) {
            return VoterWinTimeHStoreAnotherConstants.ERR_INVALID_CONTESTANT;
        }
		
        if ((validation[1].getRowCount() == 1) &&
			(validation[1].asScalarLong() >= maxVotesPerPhoneNumber)) {
            return VoterWinTimeHStoreAnotherConstants.ERR_VOTER_OVER_VOTE_LIMIT;
        }
		
        // Some sample client libraries use the legacy random phone generation that mostly
        // created invalid phone numbers. Until refactoring, re-assign all such votes to
        // the "XX" fake state (those votes will not appear on the Live Statistics dashboard,
        // but are tracked as legitimate instead of invalid, as old clients would mostly get
        // it wrong and see all their transactions rejected).
        final String state = (validation[2].getRowCount() > 0) ? validation[2].fetchRow(0).getString(0) : "XX";
		 
        //long maxStageTimestamp = validation[3].fetchRow(0).getLong(0);
        long maxStageTimestamp = currentTimestamp;
        long minWinTimestamp = (validation[3].getRowCount() > 0) ? validation[3].fetchRow(0).getLong(0) : 0 ;
        // Post the vote
        //TimestampType timestamp = new TimestampType();
        
        //validation = voltExecuteSQL();
        
        
        
        if(maxStageTimestamp - minWinTimestamp >= VoterWinTimeHStoreAnotherConstants.WINDOW_SIZE + VoterWinTimeHStoreAnotherConstants.SLIDE_SIZE)
        {
        	//Check the window size and cutoff vote can be done one of two ways:
        	//1) Two statements: one gets window size, one gets all rows to be deleted
        	//2) Return full window to Java, and let it sort it out.  Better for large slides.
        	//Likewise, either of these methods can be called in the earlier batch if that's better.
        	//voltQueueSQL(selectFullWindowStmt);

        	//validation = voltExecuteSQL();
        	voltQueueSQL(deleteCutoffVoteStmt, maxStageTimestamp - VoterWinTimeHStoreAnotherConstants.WINDOW_SIZE);
        	voltQueueSQL(insertVoteWindowStmt);
        	//voltQueueSQL(deleteMostRecentVote, maxStageTimestamp);
    		//voltQueueSQL(deleteLeaderBoardStmt);
    		//voltQueueSQL(updateLeaderBoardStmt);
    		voltQueueSQL(deleteStagingStmt);
    		//voltExecuteSQL(true);
        }
        voltQueueSQL(insertVoteStmt, voteId, phoneNumber, state, contestantNumber, currentTimestamp);
        voltQueueSQL(insertVoteStagingStmt, voteId, phoneNumber, state, contestantNumber, currentTimestamp);
        voltExecuteSQL(true);
        
        // Set the return value to 0: successful vote
        return VoterWinTimeHStoreAnotherConstants.VOTE_SUCCESSFUL;
    }
}
