/***************************************************************************
 *  Copyright (C) 2009 by H-Store Project                                  *
 *  Brown University                                                       *
 *  Massachusetts Institute of Technology                                  *
 *  Yale University                                                        *
 *                                                                         *
 *  Original Version:                                                      *
 *  Zhe Zhang (zhe@cs.brown.edu)                                           *
 *  http://www.cs.brown.edu/~zhe/                                          *
 *                                                                         *
 *  Modifications by:                                                      *
 *  Andy Pavlo (pavlo@cs.brown.edu)                                        *
 *  http://www.cs.brown.edu/~pavlo/                                        *
 *                                                                         *
 *  Modifications by:                                                      *
 *  Alex Kalinin (akalinin@cs.brown.edu)                                   *
 *  http://www.cs.brown.edu/~akalinin/                                     *
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
package edu.brown.benchmark.tpceb.procedures;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;
import org.voltdb.VoltTableRow;
import org.voltdb.VoltType;

/**
 * Market-Feed Transaction. <br>
 * TPC-E section: 3.3.9
 * 
 * H-Store quirks:
 *   1) Instead of using the send_to_market interface, we return the result as a table. Then the calling MEE client
 *      will use it to do its job. 
 */
public class MarketFeed extends VoltProcedure {
    public int count = 0;
    private final VoltTable stm_template = new VoltTable(
            new VoltTable.ColumnInfo("symbol", VoltType.STRING),
            new VoltTable.ColumnInfo("trade_id", VoltType.BIGINT),
            new VoltTable.ColumnInfo("price_quote", VoltType.FLOAT),
            new VoltTable.ColumnInfo("trade_qty", VoltType.INTEGER),
            new VoltTable.ColumnInfo("trade_type", VoltType.STRING)
    );

    private static class TradeRequest {
        public String symbol;
        public long trade_id;
        public double price_quote;
        public int trade_qty;
        public String trade_type;

        public TradeRequest(String symbol, long trade_id, double price_quote, int trade_qty, String trade_type) {
            this.symbol = symbol;
            this.trade_id = trade_id;
            this.price_quote = price_quote;
            this.trade_qty = trade_qty;
            this.trade_type = trade_type;
        }
    }

    private static int MAX_FEED_LEN = 20;
    private static int MAX_SEND_LEN = 40;

    public final SQLStmt updateLastTrade = new SQLStmt("update LAST_TRADE set LT_PRICE = ?, LT_VOL = LT_VOL + ?, LT_DTS = ? where LT_S_SYMB = ?");
   // public final SQLStmt updateLastTrade = new SQLStmt("select LT_PRICE from LAST_TRADE where LT_S_SYMB = ?");
    public final SQLStmt getRequestList = new SQLStmt("select TR_T_ID, TR_BID_PRICE, TR_TT_ID, TR_QTY from TRADE_REQUEST " +
            "where TR_S_SYMB = ? and ((TR_TT_ID = ? and TR_BID_PRICE >= ?) or " +
            "(TR_TT_ID = ? and TR_BID_PRICE <= ?) or " +
            "(TR_TT_ID = ? and TR_BID_PRICE >= ?))");

    public final SQLStmt updateTrade = new SQLStmt("update TRADE set T_DTS = ?, T_ST_ID = ? where T_ID = ?");

    public final SQLStmt deleteTradeRequest = new SQLStmt("delete from TRADE_REQUEST where TR_T_ID = ?");

    public final SQLStmt insertTradeHistory = new SQLStmt("insert into TRADE_HISTORY (TH_T_ID, TH_DTS, TH_ST_ID) values (?, ?, ?)");

    public VoltTable[] run(double[] price_quotes, String status_submitted, String[] symbols, long[] trade_qtys, String type_limit_buy, String type_limit_sell, String type_stop_loss)
            throws VoltAbortException {
        System.out.println("in market feed");
        
      //  Date now_dts = Calendar.getInstance().getTime();
        long now_dts = Calendar.getInstance().getTimeInMillis();
      //  Timestamp test = new Timestamp(now_dts);
        List<TradeRequest> tradeRequestBuffer = new ArrayList<TradeRequest>();
       // System.out.println("got date time and made list");
        // let's do the updates first in a batch
        for (int i = 0; i < MAX_FEED_LEN; i++) {
           // System.out.println("i: "+ i);
            
           //     System.out.println("Symbols"+ symbols[i]+ " "+ symbols[i].length());
            
           
           //  System.out.println("price quote"+ price_quotes[i] );
            // System.out.println("trade qtys"+ trade_qtys[i] );
            // System.out.println(now_dts);
            // System.out.println("Symbols"+ symbols[i]+ " "+ symbols[i].length());
           voltQueueSQL(updateLastTrade, price_quotes[i], trade_qtys[i], now_dts, symbols[i]);
           // voltQueueSQL(updateLastTrade, symbols[i]);
           // System.out.println("queued sql");
        }
       // System.out.println("out of for loop");
       // try{
        //    System.out.println("in try");
        voltExecuteSQL();

       // System.out.println("executed the sql for update last trade successfully");
        
        // then, see about pending trades
        for (int i = 0; i < MAX_FEED_LEN; i++) {
            voltQueueSQL(getRequestList, symbols[i], type_stop_loss, price_quotes[i],
                    type_limit_sell, price_quotes[i],
                    type_limit_buy, price_quotes[i]);
         // System.out.println("Symbol"+ symbols[i]);
        //  System.out.println("PQ"+ price_quotes[i]);
            /*public final SQLStmt getRequestList = new SQLStmt("select TR_T_ID, TR_BID_PRICE, TR_TT_ID, TR_QTY from TRADE_REQUEST " +
            "where TR_S_SYMB = ? and ((TR_TT_ID = ? and TR_BID_PRICE >= ?) or " +
            "(TR_TT_ID = ? and TR_BID_PRICE <= ?) or " +
            "(TR_TT_ID = ? and TR_BID_PRICE >= ?))");*/
            VoltTable reqs = voltExecuteSQL()[0];
           // System.out.println("executed the sql for get request list successfully" + reqs.getRowCount());
            
            for (int j = 0; j < reqs.getRowCount() && tradeRequestBuffer.size() < MAX_SEND_LEN; j++) {
                VoltTableRow req = reqs.fetchRow(j);
                
                long trade_id = req.getLong("TR_T_ID");
                System.out.println("did trade id" +trade_id);
                double price_quote = req.getDouble("TR_BID_PRICE");
              //  System.out.println("did trade idprice");
                String trade_type = req.getString("TR_TT_ID");
               // System.out.println("did trade type");
               
               // try{
                int trade_qty = (int)  req.getLong("TR_QTY");
                //   int trade_qty = (int) req.get("TR_QTY", VoltType.INTEGER);
               // }catch(Exception ex){
               //     System.out.println(ex);
               //     }
              //  System.out.println("did trade qty" + trade_qty);
               // System.out.println("did trade type" + trade_type);
               voltQueueSQL(updateTrade, now_dts, status_submitted, trade_id);
               //System.out.println("Fine");
               // System.out.println("TRADE ID:"+ trade_id);
                voltQueueSQL(deleteTradeRequest, trade_id);
               /// System.out.println("queued");
               voltQueueSQL(insertTradeHistory, trade_id, now_dts, status_submitted);
                voltExecuteSQL();
                count++;
                System.out.println("executed the sql for update trade, delete req and insert hist successfully");
                TradeRequest tr = new TradeRequest(symbols[i], trade_id, price_quote, trade_qty, trade_type);
                tradeRequestBuffer.add(tr);
            }
            
        }
        System.out.println("COUNTOUT" + count);
        // creating send_to_market info
        VoltTable stm = stm_template.clone(512);
        int j =0;
        System.out.println("size of trb"+ tradeRequestBuffer.size());
        for (TradeRequest req: tradeRequestBuffer) {
            Integer newInt = new Integer(req.trade_qty);
            System.out.println("eAction? " + req.trade_type);
            stm.addRow(req.symbol, req.trade_id, req.price_quote, newInt, req.trade_type);
            System.out.println("added row"+ j);
            j++;
        }
        System.out.println("DONE!");
       
            return new VoltTable[] {stm};
       
       
    }
}
