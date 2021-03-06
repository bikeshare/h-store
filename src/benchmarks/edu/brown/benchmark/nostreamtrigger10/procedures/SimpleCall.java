package edu.brown.benchmark.nostreamtrigger10.procedures;

import org.voltdb.ProcInfo;
import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;
import org.voltdb.types.TimestampType;

@ProcInfo(singlePartition = true)
public class SimpleCall extends VoltProcedure {

    public final SQLStmt insertS1 = new SQLStmt("INSERT INTO S1 (value) VALUES (0);");

    public final SQLStmt insertS2 = new SQLStmt("INSERT INTO S2 (value) SELECT * FROM S1;");

    public final SQLStmt insertS3 = new SQLStmt("INSERT INTO S3 (value) SELECT * FROM S2;");

    public final SQLStmt insertS4 = new SQLStmt("INSERT INTO S4 (value) SELECT * FROM S3;");

    public final SQLStmt insertS5 = new SQLStmt("INSERT INTO S5 (value) SELECT * FROM S4;");

    public final SQLStmt insertS6 = new SQLStmt("INSERT INTO S6 (value) SELECT * FROM S5;");

    public final SQLStmt insertS7 = new SQLStmt("INSERT INTO S7 (value) SELECT * FROM S6;");

    public final SQLStmt insertS8 = new SQLStmt("INSERT INTO S8 (value) SELECT * FROM S7;");

    public final SQLStmt insertS9 = new SQLStmt("INSERT INTO S9 (value) SELECT * FROM S8;");

    public final SQLStmt insertS10 = new SQLStmt("INSERT INTO S10 (value) SELECT * FROM S9;");

    public final SQLStmt insertS11 = new SQLStmt("INSERT INTO S11 (value) SELECT * FROM S10;");


    // delete statements
    public final SQLStmt deleteS1 = new SQLStmt("DELETE FROM S1;");

    public final SQLStmt deleteS2 = new SQLStmt("DELETE FROM S2;");

    public final SQLStmt deleteS3 = new SQLStmt("DELETE FROM S3;");

    public final SQLStmt deleteS4 = new SQLStmt("DELETE FROM S4;");

    public final SQLStmt deleteS5 = new SQLStmt("DELETE FROM S5;");

    public final SQLStmt deleteS6 = new SQLStmt("DELETE FROM S6;");

    public final SQLStmt deleteS7 = new SQLStmt("DELETE FROM S7;");

    public final SQLStmt deleteS8 = new SQLStmt("DELETE FROM S8;");

    public final SQLStmt deleteS9 = new SQLStmt("DELETE FROM S9;");

    public final SQLStmt deleteS10 = new SQLStmt("DELETE FROM S10;");

    public long run() {

        voltQueueSQL(insertS1);
        voltExecuteSQL();

        voltQueueSQL(insertS2);
        voltExecuteSQL();

        voltQueueSQL(insertS3);
        voltExecuteSQL();

        voltQueueSQL(insertS4);
        voltExecuteSQL();

        voltQueueSQL(insertS5);
        voltExecuteSQL();

        voltQueueSQL(insertS6);
        voltExecuteSQL();

        voltQueueSQL(insertS7);
        voltExecuteSQL();

        voltQueueSQL(insertS8);
        voltExecuteSQL();

        voltQueueSQL(insertS9);
        voltExecuteSQL();

        voltQueueSQL(insertS10);
        voltExecuteSQL();

        voltQueueSQL(insertS11);
        voltExecuteSQL();

        //delete
        voltQueueSQL(deleteS10);
        voltExecuteSQL();

        voltQueueSQL(deleteS9);
        voltExecuteSQL();

        voltQueueSQL(deleteS8);
        voltExecuteSQL();

        voltQueueSQL(deleteS7);
        voltExecuteSQL();

        voltQueueSQL(deleteS6);
        voltExecuteSQL();

        voltQueueSQL(deleteS5);
        voltExecuteSQL();

        voltQueueSQL(deleteS4);
        voltExecuteSQL();

        voltQueueSQL(deleteS3);
        voltExecuteSQL();

        voltQueueSQL(deleteS2);
        voltExecuteSQL();

        voltQueueSQL(deleteS1);
        voltExecuteSQL();

        return 0;
    }
}
