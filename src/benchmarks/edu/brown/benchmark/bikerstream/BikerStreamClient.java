/***************************************************************************
 *  Copyright (C) 2012 by H-Store Project                                  *
 *  Brown University                                                       *
 *  Massachusetts Institute of Technology                                  *
 *  Yale University                                                        *
 *  Portland State University                                              *
 *                                                                         *
 *  Original By: VoltDB Inc.                                               *
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

package edu.brown.benchmark.bikerstream;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

import org.voltdb.client.Client;
import org.voltdb.client.ClientResponse;
import org.voltdb.client.ProcedureCallback;
import org.voltdb.types.TimestampType;

import edu.brown.api.BenchmarkComponent;

import edu.brown.benchmark.bikerstream.BikeRider.*;

public class BikerStreamClient extends BenchmarkComponent {

    // Bike Readings
    AtomicLong failure = new AtomicLong(0);

    public static void main(String args[]) {
        BenchmarkComponent.main(BikerStreamClient.class, args, false);
    }

    public BikerStreamClient(String args[]) {
        super(args);
    }

    @Override
    public void runLoop() {
        try {
            while (true) {
                try {
                    runOnce();
                } catch (Exception e) {
                    failure.incrementAndGet();
                }

            } // WHILE
        } catch (Exception e) {
            // Client has no clean mechanism for terminating with the DB.
            e.printStackTrace();
        }
    }

    @Override
    protected boolean runOnce() throws IOException {

        // gnerate a client class struct for the current thread
        Client client = this.getClientHandle();

        // Bike reading generator
        try {

            // Generate a random Rider ID
            long rider_id = (int) (Math.random() * 100000);

            // Create a new Rider Struct
            BikeRider rider = new BikeRider(rider_id);

            // Checkout a bike from the Biker's initial station
            client.callProcedure(new BikerCallback(BikerStreamConstants.CheckoutCallback, rider_id),
                    "CheckoutBike",
                    rider.getRiderId(),
                    rider.getStartingStation());

            // Sign the rider up, by sticking rider information into the DB
            client.callProcedure(new SignUpCallback(), "SignUp",  rider.getRiderId());
            client.callProcedure(new TestCallback(5), "LogRiderTrip", rider_id, startStation, endStation);
            client.callProcedure(new TestCallback(4), "TestProcedure");
            client.callProcedure(new CheckoutCallback(), "CheckoutBike",  rider.getRiderId(), rider.getStartingStation());

            Reading point;
            long time_t;

            // The biker trip is deivided into legs. after each leg the biker will stop to see if any nearby stations
            // are providing discounts. If so the biker should deviate toward that station. If there are no more legs
            // available then the rider is considered to be at the final destination and should attempt to checkin the
            // bike.
            while ((route = rider.getNextRoute()) != null) {

                // As long as there are points in the current leg, put them into the data base at a rate specified by
                // the MILI_BETWEEN_GPS_EVENTS Constant.
                while ((point = route.poll()) != null){
                    client.callProcedure(new BikerCallback(BikerStreamConstants.RideBikeCallback),
                            "RideBike",
                            rider.getRiderId(),
                            point.lat,
                            point.lon);
                    long lastTime = (new TimestampType()).getMSTime();
                    while (((new TimestampType()).getMSTime()) - lastTime < BikerStreamConstants.MILI_BETWEEN_GPS_EVENTS) {}
                }

                point = rider.getPoint();
                client.callProcedure(new RideCallback(), "RideBike",  rider.getRiderId(), point.lat, point.lon);

            // When all legs of the journey are finished. Attempt to park the bike. The callback will handle whether or
            //not we were successfull.
            client.callProcedure(new BikerCallback(BikerStreamConstants.CheckinCallback, rider_id),
                    "CheckinBike",
                    rider.getRiderId(),
                    rider.getFinalStation());

            // The handle will insert our bike id into the hashmap when we are successful.
            while (!(checkedBikeSet.contains(rider_id))){

                // Deviate course if we did not appear in the hashmap. We will receive a new route to the next station.
                route = rider.deviateRandomly();

                // Put those points into the DB
                while ((point = route.poll()) != null){
                    client.callProcedure(new BikerCallback(BikerStreamConstants.RideBikeCallback),
                            "RideBike",
                            rider.getRiderId(),
                            point.lat,
                            point.lon);
                    long lastTime = (new TimestampType()).getMSTime();
                    while (((new TimestampType()).getMSTime()) - lastTime < BikerStreamConstants.MILI_BETWEEN_GPS_EVENTS) {}
                }

                // Try again to checkin the bike. Loop to check for success
                client.callProcedure(new BikerCallback(BikerStreamConstants.CheckinCallback, rider_id),
                        "CheckinBike",
                        rider.getRiderId(),
                        rider.getFinalStation());
            }

                client.callProcedure(new CheckinCallback(), "CheckinBike",  rider.getRiderId(), rider.getFinalStation());

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }

        return true;
    }

    @Override
    public String[] getTransactionDisplayNames() {
        // Return an array of transaction names
        String procNames[] = new String[]{
            "Riders signed up",
            "Bikes checked out",
            "Points added to the DB",
            "Bikes checked in",
        };
        return (procNames);
    }


    private class SignUpCallback implements ProcedureCallback {

        @Override
        public void clientCallback(ClientResponse clientResponse) {
            // Increment the BenchmarkComponent's internal counter on the
            // number of transactions that have been completed
            incrementTransactionCounter(clientResponse, 0);

        }

    } // END CLASS

    private class CheckoutCallback implements ProcedureCallback {

        @Override
        public void clientCallback(ClientResponse clientResponse) {
            // Increment the BenchmarkComponent's internal counter on the
            // number of transactions that have been completed
            incrementTransactionCounter(clientResponse, 1);

        }

    } // END CLASS

    private class RideCallback implements ProcedureCallback {

        @Override
        public void clientCallback(ClientResponse clientResponse) {
            // Increment the BenchmarkComponent's internal counter on the
            // number of transactions that have been completed
            incrementTransactionCounter(clientResponse, 2);

        }

    } // END CLASS

    private class CheckinCallback implements ProcedureCallback {

        @Override
        public void clientCallback(ClientResponse clientResponse) {
            // Increment the BenchmarkComponent's internal counter on the
            // number of transactions that have been completed
            incrementTransactionCounter(clientResponse, 3);

        }

    } // END CLASS

    private class TestCallback implements ProcedureCallback {

        private int procNum;

        public TestCallback(int numProc) {
            procNum = numProc;
        }

        @Override
        public void clientCallback(ClientResponse clientResponse) {
            incrementTransactionCounter(clientResponse, procNum);
        }


    } // END CLASS
}
