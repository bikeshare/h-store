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
// contestant and that the voterhstoreeetrigtest (phone number of the caller) is not above the
// number of allowed votes.
//

package edu.brown.benchmark.voterhstoreeetrigtest.procedures;

import org.voltdb.ProcInfo;
import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;
import org.voltdb.types.TimestampType;

import edu.brown.benchmark.voterhstoreeetrigtest.VoterHStoreEETrigTestConstants;

@ProcInfo (
    partitionInfo = "votes.phone_number:1",
    singlePartition = true
)
public class Vote extends VoltProcedure {

	
    // Checks if the vote is for a valid contestant
    public final SQLStmt checkContestantStmt = new SQLStmt(
	   "SELECT contestant_number FROM contestants WHERE contestant_number = ?;"
    );
	
    // Records a vote
    public final SQLStmt insertVoteStmt = new SQLStmt(
		"INSERT INTO votes (vote_id, phone_number, state, contestant_number, ts) VALUES (?, ?, ?, ?, ?);"
    );

    
	
    public long run(long voteId, long phoneNumber, int contestantNumber, long maxVotesPerPhoneNumber, int currentTimestamp) {
		
        // Queue up validation statements
        voltQueueSQL(checkContestantStmt, contestantNumber);
        VoltTable validation[] = voltExecuteSQL();
		
        if (validation[0].getRowCount() == 0) {
            return VoterHStoreEETrigTestConstants.ERR_INVALID_CONTESTANT;
        }
		
        
        voltQueueSQL(insertVoteStmt, voteId, phoneNumber, "XX", contestantNumber, currentTimestamp);
        voltExecuteSQL(true);
        
        // Set the return value to 0: successful vote
        return VoterHStoreEETrigTestConstants.VOTE_SUCCESSFUL;
    }
}