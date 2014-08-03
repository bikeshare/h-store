package edu.brown.benchmark.bikerstream.procedures;

import org.apache.log4j.Logger;
import org.voltdb.SQLStmt;
import org.voltdb.VoltTrigger;


public class CalculateSpeed extends VoltTrigger {

    @Override
    protected String toSetStreamName() { return "w1"; }

    // TODO:  Cannot use subquery to compute the speed for each user.  Also cannot manual compute with Java code.
    // Also it seems wrong to re-calculate speed of every user in the WINDOW because we don't know who is the last user.

    // How to compute the speed for a given user or each user using only a simple SQL,
    // 'INSERT INTO ... SELECT ...' statement?

    // Using dummy COUNT(*) in place of speed
    public final SQLStmt feedS2Stream = new SQLStmt(
            "INSERT INTO s2 (user_id, speed) SELECT user_id, COUNT(*) FROM w1 GROUP BY user_id;"
    );

}
