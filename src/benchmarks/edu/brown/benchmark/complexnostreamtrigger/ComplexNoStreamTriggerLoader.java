package edu.brown.benchmark.complexnostreamtrigger;

import org.apache.log4j.Logger;

import edu.brown.api.Loader;

public class ComplexNoStreamTriggerLoader extends Loader {

    private static final Logger LOG = Logger.getLogger(ComplexNoStreamTriggerLoader.class);
    private static final boolean d = LOG.isDebugEnabled();

    public static void main(String args[]) throws Exception {
        if (d) LOG.debug("MAIN: " + ComplexNoStreamTriggerLoader.class.getName());
        Loader.main(ComplexNoStreamTriggerLoader.class, args, true);
    }

    public ComplexNoStreamTriggerLoader(String[] args) {
        super(args);
    }

    @Override
    public void load() {
        try {
            this.getClientHandle().callProcedure("Initialize",
                                                 1000);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

