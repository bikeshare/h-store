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
public class CheckoutBike extends VoltProcedure {

    // Logging Information
    private static final Logger Log = Logger.getLogger(CheckoutBike.class);
    // Is debugging on or not?
    final boolean debug = Log.isDebugEnabled();

    public final SQLStmt getStation = new SQLStmt(
                "SELECT * FROM stationstatus WHERE station_id = ?"
            );
    
    public final SQLStmt checkUser = new SQLStmt(
    			"SELECT COUNT(*) FROM bikes WHERE user_id = ?"
    		);

    public final SQLStmt updateStation = new SQLStmt(
                "UPDATE stationstatus SET current_bikes = ?, current_docks = ? WHERE station_id = ?"
            );
    
    public final SQLStmt getBike = new SQLStmt(
                "SELECT bike_id FROM bikes WHERE station_id = ? AND current_status = 0"
            );
   
    public final SQLStmt assignBike = new SQLStmt(
                "UPDATE bikes SET station_id = 'NULL', user_id = ?, current_status = 1 WHERE bike_id = ?"
            );

    public final SQLStmt log = new SQLStmt(
                "INSERT INTO logs (user_id, time, success, action) VALUES (?,?,?,?)"
            );



    public long run(long rider_id, long station_id) throws Exception {

        voltQueueSQL(getStation, station_id);
        VoltTable results[] = voltExecuteSQL();

        assert(results[0].getRowCount() == 1);

        long numBikes = results[0].fetchRow(0).getLong("current_bikes");
        long numDocks = results[0].fetchRow(0).getLong("current_docks");
        
        voltQueueSQL(checkUser, rider_id);
        results = voltExecuteSQL();
        
        if (results[0].getRowCount() < 1) {
        	voltQueueSQL(log, rider_id, new TimestampType(), 0, "could not get bike from station: " + station_id);
            throw new RuntimeException("There are no bikes availible at station: " + station_id);
        }
        
        long bikesUserCheckedOut = results[0].fetchRow(0).getLong(0);

        if (bikesUserCheckedOut >= 1)
        	throw new Exception("User " + rider_id + " already has a bike checked out");
        if (numBikes > 0){
            voltQueueSQL(updateStation, --numBikes, ++numDocks, station_id);
            voltQueueSQL(log, rider_id, new TimestampType(), 1, "successfully got bike from station: " + station_id);
            voltExecuteSQL();
            voltQueueSQL(getBike, station_id);
            VoltTable [] bikes = voltExecuteSQL();
            long bike_id = bikes[0].fetchRow(0).getLong(0);
            voltQueueSQL(assignBike, rider_id, bike_id);
            voltExecuteSQL();
            return 1;
        } else {
            voltQueueSQL(log, rider_id, new TimestampType(), 0, "could not get bike from station: " + station_id);
            throw new RuntimeException("There are no bikes availible at station: " + station_id);
        }

    }

} // End Class

