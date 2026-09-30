package io.github.hronosin.miracle;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;

/**
 * The loader jar's Premain-Class, compiled for Java 8 like {@link Resurrection}, so that an older
 * Java can at least say why nothing happens. On Java 25 or newer it hands over to AgentMain.
 */
public final class Agent {

    private Agent() {
    }

    public static void premain(String args, Instrumentation inst) throws Throwable {
        int running = Resurrection.major(System.getProperty("java.specification.version"));
        if (running < Resurrection.NEEDED) {
            System.err.println("[Miracle] MiracleLoader (as a Java agent) needs Java " + Resurrection.NEEDED
                    + " or newer, and this game was started with Java " + running + ".");
            System.err.println("[Miracle] Give this profile a Java " + Resurrection.NEEDED + ": in the official launcher,"
                    + " Installations > Edit > More options > Java executable; in Prism Launcher, the instance's Settings > Java."
                    + " Or start the game with io.github.hronosin.miracle.Resurrection as the main class instead of the agent,"
                    + " which finds a Java " + Resurrection.NEEDED + " by itself.");
            System.exit(1);
        }
        try {
            Class.forName("io.github.hronosin.miracle.AgentMain")
                    .getMethod("premain", String.class, Instrumentation.class)
                    .invoke(null, args, inst);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
