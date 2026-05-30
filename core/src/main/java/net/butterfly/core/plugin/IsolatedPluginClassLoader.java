package net.butterfly.core.plugin;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * Per-plugin classloader.
 *
 * <p>Standard JDK packages and the published Butterfly api are always
 * delegated to the parent so every plugin sees the same {@code Plugin},
 * {@code Logger}, {@code Server}, etc. classes. Everything else is loaded
 * from this loader's URLs first, falling back to the parent only when not
 * found locally — that keeps plugins isolated from each other while still
 * letting them call into the api.
 */
public final class IsolatedPluginClassLoader extends URLClassLoader {

    static {
        ClassLoader.registerAsParallelCapable();
    }

    private final ClassLoader apiClassLoader;

    public IsolatedPluginClassLoader(URL[] urls, ClassLoader apiClassLoader) {
        super(urls, apiClassLoader);
        this.apiClassLoader = apiClassLoader;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                if (resolve) resolveClass(loaded);
                return loaded;
            }

            if (isSharedClass(name)) {
                Class<?> c = apiClassLoader.loadClass(name);
                if (resolve) resolveClass(c);
                return c;
            }

            // Plugin-local class: try this loader's URLs first.
            try {
                Class<?> c = findClass(name);
                if (resolve) resolveClass(c);
                return c;
            } catch (ClassNotFoundException ignored) {
                // Fall through to parent.
            }

            Class<?> c = apiClassLoader.loadClass(name);
            if (resolve) resolveClass(c);
            return c;
        }
    }

    private static boolean isSharedClass(String name) {
        return name.startsWith("java.")
                || name.startsWith("javax.")
                || name.startsWith("jdk.")
                || name.startsWith("org.slf4j.")
                || name.startsWith("net.butterfly.api.");
    }
}
