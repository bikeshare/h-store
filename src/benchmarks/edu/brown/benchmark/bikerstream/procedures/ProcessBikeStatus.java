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


package edu.brown.benchmark.bikerstream.procedures;

import org.apache.log4j.Logger;
import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;

/**
 * This VoltProcedure will trigger on INSERT INTO bikeStatus STREAM and performs the following;
 *   a.  Insert into the riderPositions TABLE
 *   b.  Pass the new data into s3 STREAM
 *   c.  Pass the new data into w1 WINDOW
 */
public class ProcessBikeStatus extends VoltProcedure {
    private static final Logger LOG = Logger.getLogger(ProcessBikeStatus.class);

    protected void toSetTriggerTableName() {
        addTriggerTable("bikeStatus");
    }

    public final SQLStmt insertRiderPositions = new SQLStmt(
            "INSERT INTO riderPositions (user_id, latitude, longitude) " +
                    "SELECT              user_id, latitude, longitude FROM bikeStatus;"
    );

    public final SQLStmt feedS3Stream = new SQLStmt(
            "INSERT INTO s3 (user_id, latitude, longitude) " +
                    "SELECT  user_id, latitude, longitude FROM bikeStatus;"
    );

    public final SQLStmt feedW1Stream = new SQLStmt(
            "INSERT INTO w1 (user_id, latitude, longitude, time) " +
                    "SELECT  user_id, latitude, longitude, time FROM bikeStatus;"
    );

    public final SQLStmt removeUsedBikeStatusTuple = new SQLStmt(
            "DELETE FROM bikeStatus;"
    );

    public final SQLStmt checkW1 = new SQLStmt(
            "SELECT user_id, COUNT(*) AS entry_count, MAX(time) AS max_time FROM w1 GROUP BY user_id;"
    );

    public long run() {
        LOG.debug(" >>> Start running " + this.getClass().getSimpleName());
        voltQueueSQL(insertRiderPositions);
        voltExecuteSQL();

        voltQueueSQL(feedS3Stream);
        voltExecuteSQL();

        voltQueueSQL(feedW1Stream);
        voltExecuteSQL();

        // For verification purpose
        voltQueueSQL(checkW1);
        LOG.info("Summary of w1 WINDOW's content: "  + voltExecuteSQL()[0]);


        voltQueueSQL(removeUsedBikeStatusTuple);
        voltExecuteSQL(true);

        LOG.info(" <<< Finished running " + this.getClass().getSimpleName());
        return 0;
    }
}
