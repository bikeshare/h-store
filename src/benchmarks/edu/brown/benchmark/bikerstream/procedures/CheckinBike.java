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
// Rides a bike - basically inserts events theoretically
// from a gps on a bike into the bikerreadings_stream
//

package edu.brown.benchmark.bikerstream.procedures;

import org.apache.log4j.Logger;
import org.voltdb.ProcInfo;
import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;
import org.voltdb.VoltType;
import org.voltdb.types.TimestampType;

import edu.brown.benchmark.bikerstream.BikerStreamConstants;

@ProcInfo (
    singlePartition = true
)
public class CheckinBike extends VoltProcedure {

    // Logging Information
    private static final Logger Log = Logger.getLogger(CheckinBike.class);
    // Is debugging on or not?
    final boolean debug = Log.isDebugEnabled();
    
    public final SQLStmt getUser = new SQLStmt(
    			"SELECT * FROM users WHERE user_id = ?"
    		);

    public final SQLStmt getStation = new SQLStmt(
        "SELECT * FROM stationstatus where station_id = ?"
    );

    public final SQLStmt updateStation = new SQLStmt(
        "UPDATE stationstatus SET current_bikes = ?, current_docks = ? where station_id = ?"
    );

    public final SQLStmt updateStationDiscount = new SQLStmt(
                "UPDATE stationstatus SET current_bikes = ?, current_docks = ?, current_discount = ? where station_id = ?"
            );

    public final SQLStmt getBike = new SQLStmt(
                "SELECT bike_id FROM bikes WHERE user_id = ?"
            );

    public final SQLStmt returnBike = new SQLStmt(
                "UPDATE bikes SET user_id = 'NULL', station_id = ?, current_status = 1 WHERE bike_id = ?"
            );

    public final SQLStmt removeFromLocation = new SQLStmt(
                "DELETE from userLocations WHERE user_id = ?"
            );

    public final SQLStmt log = new SQLStmt(
        "INSERT INTO logs (user_id, time, success, action) VALUES (?,?,?,?)"
    );

    public final SQLStmt  checkDiscount = new SQLStmt (
        "SELECT count(*) FROM discounts WHERE user_id = ?"
    );


    public long run(long rider_id, long station_id) throws Exception {

    	voltQueueSQL(getUser, rider_id);
    	VoltTable results[] = voltExecuteSQL();
    	if (results[0].getRowCount() < 1)
            return BikerStreamConstants.USER_DOESNT_EXIST;
    		//throw new RuntimeException("Rider: " + rider_id + " does not exist");
    	
        voltQueueSQL(getStation, station_id);
        voltQueueSQL(checkDiscount, rider_id);
        results = voltExecuteSQL();

        //assert(results[0].getRowCount() == 1);

        long numBikes = results[0].fetchRow(0).getLong("current_bikes");
        long numDocks = results[0].fetchRow(0).getLong("current_docks");
        long numDiscounts = results[0].fetchRow(0).getLong("current_discount");
        boolean hasDiscount = results[1].asScalarLong() > 0;

        if (numDocks > 0){

            voltQueueSQL(getBike, rider_id);
            VoltTable bikes[] = voltExecuteSQL();
            if (bikes[0].getRowCount() >= 1) {
                Log.info("Checking into station: " + station_id + " by user: " + rider_id);
                if (numDiscounts > 0 && !(hasDiscount)){
                    voltQueueSQL(updateStationDiscount, ++numBikes, --numDocks, numDiscounts -1, station_id);
                    Log.info("Rider: " + rider_id + " Checking in and Removing discount from station: " + station_id);
                } else {
                    voltQueueSQL(updateStation, ++numBikes, --numDocks, station_id);
                    Log.info("Rider: " + rider_id + " Checking in and *not* Removing discount from station: " + station_id);
                }
                long bike_id = bikes[0].fetchRow(0).getLong(0);
                voltQueueSQL(returnBike, station_id, bike_id);
                voltQueueSQL(removeFromLocation, rider_id);
                voltExecuteSQL();
            }
            else
                return BikerStreamConstants.NO_BIKE_CHECKED_OUT;
                //throw new Exception("Rider " + rider_id + " does not have a bike checked out");

            voltQueueSQL(log, rider_id, new TimestampType(), 1, "successfully docked bike at station: " + station_id);
            voltExecuteSQL(true);
            return BikerStreamConstants.CHECKIN_SUCCESS;

        } else {
            Log.info("Could not checkinto station: " + station_id + " by rider: " + rider_id);
            voltQueueSQL(log, rider_id, new TimestampType(), 0, "could not dock bike at station: " + station_id);
            voltExecuteSQL(true);
            return BikerStreamConstants.FAILED_CHECKIN;
        }

    }

} // End Class

