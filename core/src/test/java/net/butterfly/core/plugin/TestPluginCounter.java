package net.butterfly.core.plugin;

/**
 * Static counters shared between the test code and a plugin loaded through
 * {@link IsolatedPluginClassLoader}. This class lives only on the parent
 * (test) classloader; the IsolatedPluginClassLoader cannot find it among its
 * own URLs and falls back to the parent — so both the test and the loaded
 * plugin observe the same static fields.
 */
public final class TestPluginCounter {

    public static int loadCount;
    public static int enableCount;
    public static int disableCount;

    private TestPluginCounter() {}

    public static void reset() {
        loadCount = 0;
        enableCount = 0;
        disableCount = 0;
    }
}
