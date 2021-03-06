package edu.brown.benchmark.streamtrigger.procedures;

import org.voltdb.SQLStmt;
import org.voltdb.StmtInfo;
import org.voltdb.VoltTrigger;

public class SimpleTrigger34 extends VoltTrigger {

    @Override
    protected String toSetStreamName() {
        return "S34";
    }

    public final SQLStmt insertS2 = new SQLStmt(
        "INSERT INTO S35 (value) SELECT * FROM S34;"
    );
    
}
