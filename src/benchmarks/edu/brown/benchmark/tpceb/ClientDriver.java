/***************************************************************************
 *  Copyright (C) 2009 by H-Store Project                                  *
 *  Brown University                                                       *
 *  Massachusetts Institute of Technology                                  *
 *  Yale University                                                        *
 *                                                                         *
 *  Andy Pavlo (pavlo@cs.brown.edu)                                        *
 *  http://www.cs.brown.edu/~pavlo/                                        *
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
package edu.brown.benchmark.tpceb;

import edu.brown.benchmark.tpceb.TPCEConstants.DriverType;
import edu.brown.benchmark.tpceb.TPCEConstants.eMEETradeRequestAction;
import edu.brown.benchmark.tpceb.generators.*;

import java.io.File;


public class ClientDriver {
    
    public ClientDriver(String dataPath, int configuredCustomerCount, int totalCustomerCount, int scaleFactor, int initialDays){
        
//      String filename = new String("/tmp/EGenClientDriver.log");
        logFormat = new EGenLogFormatterTab();
        logger = new EGenLogger(DriverType.eDriverEGenLoader, 0, logFormat);
  
        tradeOrderTxnInput = new TTradeOrderTxnInput();

        driverCETxnSettings = new TDriverCETxnSettings();
        
        marketWatchTxnInput = new TMarketWatchTxnInput(); 
        tradeResultTxnInput = new TTradeResultTxnInput();
        marketFeedTxnInput = new TMarketFeedTxnInput();
        
        File inputDir = new File(dataPath);
        TPCEGenerator inputFiles = new TPCEGenerator(inputDir, totalCustomerCount, scaleFactor, initialDays);
        
        securityHandler = new SecurityHandler(inputFiles);
        
        //CE input generator
        sut = new SUT();
        customerEmulator = new CE(sut, logger, inputFiles, configuredCustomerCount, totalCustomerCount, scaleFactor, initialDays, 0, driverCETxnSettings);
        
        marketExchangeCallback = new MarketExchangeCallback(tradeResultTxnInput, marketFeedTxnInput);
        marketExchangeGenerator = new MEE(0, marketExchangeCallback, logger, securityHandler, 1, configuredCustomerCount);
        
     
        marketExchangeGenerator.enableTickerTape();   
        
    }
    
    public CE getCE(){
        return customerEmulator;
    }
    
    public MEE getMEE(){
        return marketExchangeGenerator;
    }
    
   public TTradeOrderTxnInput generateTradeOrderInput(int tradeType) {
        customerEmulator.getCETxnInputGenerator().generateTradeOrderInput( tradeOrderTxnInput, tradeType );
        return (tradeOrderTxnInput);
    }
   
   public TMarketWatchTxnInput generateMarketWatchInput() {
//      System.out.println("Executing generateMarketWatchInput ... \n");
        customerEmulator.getCETxnInputGenerator().generateMarketWatchInput( marketWatchTxnInput );
        return (marketWatchTxnInput);
    }
    
   public TMarketFeedTxnInput generateMarketFeedInput() {
//      System.out.println("Executing %s...\n" + "generateBrokerVolumeInput");
        return (marketFeedTxnInput);
    }
    
    public TTradeResultTxnInput generateTradeResultInput() {
//      System.out.println("Executing %s...\n" + "generateTradeResultInput");
        marketExchangeGenerator.generateTradeResult();
        return (tradeResultTxnInput);
    }

   public TTradeRequest tradeReq;
   private TTradeOrderTxnInput         tradeOrderTxnInput;
   private TTradeResultTxnInput        tradeResultTxnInput;
   private TMarketWatchTxnInput        marketWatchTxnInput;
   private TMarketFeedTxnInput         marketFeedTxnInput;
    
   private TDriverCETxnSettings        driverCETxnSettings;
   private EGenLogFormatterTab         logFormat;
   private BaseLogger                  logger;
   private CE                          customerEmulator;
   private CESUTInterface              sut;

   private MEE                         marketExchangeGenerator;
   private MEESUTInterface             marketExchangeCallback;
   private MEETradingFloor  marketExchangeTradingFloor;
    
   private SecurityHandler             securityHandler;
  

}

/*OLD CODE TRADE ORDER ONLY - REFERENCE*/
/*package edu.brown.benchmark.tpceb;

import edu.brown.benchmark.tpceb.TPCEConstants.DriverType;
import edu.brown.benchmark.tpceb.generators.*;
import java.io.File;


public class ClientDriver {
    
    public ClientDriver(String dataPath, int configuredCustomerCount, int totalCustomerCount, int scaleFactor, int initialDays){
        
//      String filename = new String("/tmp/EGenClientDriver.log");
        logFormat = new EGenLogFormatterTab();
        logger = new EGenLogger(DriverType.eDriverEGenLoader, 0, logFormat);
  
        tradeOrderTxnInput = new TTradeOrderTxnInput();

        driverCETxnSettings = new TDriverCETxnSettings();
        
        File inputDir = new File(dataPath);
        TPCEGenerator inputFiles = new TPCEGenerator(inputDir, totalCustomerCount, scaleFactor, initialDays);
        securityHandler = new SecurityHandler(inputFiles);
        
        //CE input generator
        sut = new SUT();
        cutomerEmulator = new CE(sut, logger, inputFiles, configuredCustomerCount, totalCustomerCount, scaleFactor, initialDays, 0, driverCETxnSettings);

        
    }
    public CE getCE(){
        return cutomerEmulator;
    }


    public TTradeOrderTxnInput generateTradeOrderInput(int tradeType) {
        cutomerEmulator.getCETxnInputGenerator().generateTradeOrderInput( tradeOrderTxnInput, tradeType );
        return (tradeOrderTxnInput);
    }

    private TTradeOrderTxnInput         tradeOrderTxnInput;

    private TDriverCETxnSettings        driverCETxnSettings;
    private EGenLogFormatterTab         logFormat;
    private BaseLogger                  logger;
    private CE                          cutomerEmulator;
    private CESUTInterface              sut;

    private MEESUTInterface             MEEsut;
    private SecurityHandler             securityHandler;

}*/