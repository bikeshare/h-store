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
import org.voltdb.VoltTable;


/**
 * TODO:  This should extend VoltTrigger instead of VoltProcedure
 * as its trigger source is a WINDOW.
 * When extends VoltTrigger, this trigger will not get schedule.
 * As a work around, it extends VoltProcedure instead.
 *
 * This VoltProcedure will trigger on INSERT INTO lastNBikeStatus WINDOW and
 * Aggregate the result into recentRiderArea TABLE
 */
public class LastNBikeStatusTrigger extends VoltProcedure {
    private static final Logger LOG = Logger.getLogger(LastNBikeStatusTrigger.class);

    protected void toSetTriggerTableName() {
        addTriggerTable("lastNBikeStatus");
    }

    public final SQLStmt deleteRecentRiderArea = new SQLStmt(
            "DELETE FROM recentRiderArea;"
    );

    public final SQLStmt insertRecentRiderArea = new SQLStmt(
            "INSERT INTO recentRiderArea (latitude_1, longitude_1, latitude_2, longitude_2) " +
                    "SELECT MIN(latitude), MIN(longitude), MAX(latitude), MAX(longitude) " +
                    "FROM lastNBikeStatus;"
    );

    public long run() {
        LOG.debug(" >>> Start running " + this.getClass().getSimpleName());

        voltQueueSQL(deleteRecentRiderArea);
        voltQueueSQL(insertRecentRiderArea);
        voltExecuteSQL(true);

        LOG.info(" <<< Finished running " + this.getClass().getSimpleName());
        return 0;
    }
}

