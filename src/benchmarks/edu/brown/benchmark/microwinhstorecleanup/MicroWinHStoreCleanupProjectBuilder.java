/***************************************************************************
 *  Copyright (C) 2012 by H-Store Project                                  *
 *  Brown University                                                       *
 *  Massachusetts Institute of Technology                                  *
 *  Yale University                                                        *
 *                                                                         *
 *  Coded By:  Justin A. DeBrabant (http://www.cs.brown.edu/~debrabant/)   *								   
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

package edu.brown.benchmark.microwinhstorecleanup;

import org.voltdb.VoltProcedure;

import edu.brown.benchmark.AbstractProjectBuilder;
import edu.brown.api.BenchmarkComponent;

import edu.brown.benchmark.microwinhstorecleanup.procedures.Vote; 
import edu.brown.benchmark.microwinhstorecleanup.procedures.Initialize; 

public class MicroWinHStoreCleanupProjectBuilder extends AbstractProjectBuilder {

    // REQUIRED: Retrieved via reflection by BenchmarkController
    public static final Class<? extends BenchmarkComponent> m_clientClass = MicroWinHStoreCleanupClient.class;

    // REQUIRED: Retrieved via reflection by BenchmarkController
    public static final Class<? extends BenchmarkComponent> m_loaderClass = MicroWinHStoreCleanupLoader.class;

	// a list of procedures implemented in this benchmark
    @SuppressWarnings("unchecked")
    public static final Class<? extends VoltProcedure> PROCEDURES[] = (Class<? extends VoltProcedure>[])new Class<?>[] {
        Vote.class, Initialize.class};
	
	{
		//addTransactionFrequency(Vote.class, 100);
	}
	
	// a list of tables used in this benchmark with corresponding partitioning keys
    public static final String PARTITIONING[][] = new String[][] {
        { "w_rows", "phone_number" },
        { "leaderboard", "contestant_number"},
        { "window_count", "row_id"},
        { "staging_count", "row_id"},
        { "max_win_id", "row_id"}
    };

    public MicroWinHStoreCleanupProjectBuilder() {
        super("microwinhstorecleanup", MicroWinHStoreCleanupProjectBuilder.class, PROCEDURES, PARTITIONING);
    }
}




