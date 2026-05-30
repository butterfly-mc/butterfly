package net.butterfly.core.plugin;

import net.butterfly.api.plugin.Plugin;

/**
 * Bytecode for this class is read at test runtime and packaged into a fake
 * jar so {@link PluginHost} can load it through an {@link IsolatedPluginClassLoader}.
 *
 * <p>It calls into {@link TestPluginCounter} to record lifecycle calls;
 * because the counter class lives only on the parent classloader, the loaded
 * plugin and the test see the same counter state.
 */
public class TestPlugin extends Plugin {

    @Override
    public void onLoad() {
        TestPluginCounter.loadCount++;
    }

    @Override
    public void onEnable() {
        TestPluginCounter.enableCount++;
    }

    @Override
    public void onDisable() {
        TestPluginCounter.disableCount++;
    }
}
