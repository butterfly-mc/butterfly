package net.butterfly.core.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import net.butterfly.api.plugin.PluginManifest;

/**
 * Filesystem helpers for the plugin loader: reading the {@code butterfly.yml}
 * manifest out of a jar, listing the plugin directory, and topologically
 * sorting manifests by their declared dependencies.
 */
final class PluginLoader {

    static final String MANIFEST_ENTRY = "butterfly.yml";

    private PluginLoader() {
    }

    /**
     * Read and parse the {@code butterfly.yml} manifest at the root of the
     * given jar.
     *
     * @throws PluginLoadException if the jar cannot be opened, the manifest
     *                             entry is missing, or the YAML is malformed
     *                             or missing required fields
     */
    static PluginManifest readManifest(Path jarFile) {
        if (jarFile == null) throw new NullPointerException("jarFile");
        try (JarFile jar = new JarFile(jarFile.toFile())) {
            JarEntry entry = jar.getJarEntry(MANIFEST_ENTRY);
            if (entry == null) {
                throw new PluginLoadException(
                        "plugin jar " + jarFile + " is missing required '" + MANIFEST_ENTRY + "'");
            }
            try (InputStream in = jar.getInputStream(entry)) {
                return PluginManifest.fromYaml(in);
            } catch (IllegalArgumentException ex) {
                throw new PluginLoadException(
                        "plugin jar " + jarFile + ": invalid manifest: " + ex.getMessage(), ex);
            }
        } catch (IOException ex) {
            throw new PluginLoadException("could not open plugin jar " + jarFile + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * List {@code *.jar} files directly inside {@code pluginsDir}. Returns an
     * empty list if the directory does not exist. Subdirectories are skipped.
     */
    static List<Path> scanJars(Path pluginsDir) {
        if (pluginsDir == null) throw new NullPointerException("pluginsDir");
        if (!Files.isDirectory(pluginsDir)) {
            return List.of();
        }
        List<Path> jars = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginsDir, "*.jar")) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) {
                    jars.add(entry);
                }
            }
        } catch (IOException ex) {
            throw new PluginLoadException("failed to list plugin directory " + pluginsDir + ": " + ex.getMessage(), ex);
        }
        // Stable order so load order is deterministic regardless of FS iteration order.
        jars.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
        return jars;
    }

    /**
     * Topologically sort manifests so hard dependencies come before dependents.
     * Soft dependencies add an edge only when the named plugin is also present
     * in the input list. Missing hard dependencies and cycles raise
     * {@link PluginLoadException}.
     */
    static List<PluginManifest> sortByDependencies(List<PluginManifest> manifests) {
        if (manifests == null) throw new NullPointerException("manifests");

        // Index by name; reject duplicates so the ordering is unambiguous.
        Map<String, PluginManifest> byName = new LinkedHashMap<>();
        for (PluginManifest m : manifests) {
            if (byName.put(m.name(), m) != null) {
                throw new PluginLoadException("duplicate plugin name: " + m.name());
            }
        }

        // edges: dep -> set of dependents
        Map<String, Set<String>> dependents = new HashMap<>();
        Map<String, Integer> incoming = new HashMap<>();
        for (String name : byName.keySet()) {
            dependents.put(name, new LinkedHashSet<>());
            incoming.put(name, 0);
        }

        for (PluginManifest m : byName.values()) {
            for (String dep : m.dependencies()) {
                if (!byName.containsKey(dep)) {
                    throw new PluginLoadException(
                            "plugin '" + m.name() + "' requires missing dependency '" + dep + "'");
                }
                if (dependents.get(dep).add(m.name())) {
                    incoming.merge(m.name(), 1, Integer::sum);
                }
            }
            for (String soft : m.softDependencies()) {
                if (!byName.containsKey(soft)) continue;
                if (dependents.get(soft).add(m.name())) {
                    incoming.merge(m.name(), 1, Integer::sum);
                }
            }
        }

        // Kahn's algorithm. Use a deque seeded in input order so the output
        // matches the original ordering when there are no constraints.
        Deque<String> ready = new ArrayDeque<>();
        for (String name : byName.keySet()) {
            if (incoming.get(name) == 0) ready.add(name);
        }

        List<PluginManifest> ordered = new ArrayList<>(byName.size());
        while (!ready.isEmpty()) {
            String name = ready.removeFirst();
            ordered.add(byName.get(name));
            for (String dependent : dependents.get(name)) {
                int next = incoming.merge(dependent, -1, Integer::sum);
                if (next == 0) ready.add(dependent);
            }
        }

        if (ordered.size() != byName.size()) {
            // Whatever's left has incoming > 0 — reconstruct one cycle to report.
            Set<String> remaining = new LinkedHashSet<>(byName.keySet());
            ordered.forEach(m -> remaining.remove(m.name()));
            String cycle = describeCycle(remaining, dependents);
            throw new PluginLoadException("dependency cycle: " + cycle);
        }
        return ordered;
    }

    /** Walk dependency edges from any remaining node until a node repeats; report the loop. */
    private static String describeCycle(Set<String> remaining, Map<String, Set<String>> dependents) {
        // Reverse the edges (dep -> dependent) into (node -> dependencies it has)
        // limited to nodes still in 'remaining', so we can walk backwards.
        Map<String, Set<String>> deps = new HashMap<>();
        for (String node : remaining) deps.put(node, new LinkedHashSet<>());
        for (Map.Entry<String, Set<String>> e : dependents.entrySet()) {
            String dep = e.getKey();
            if (!remaining.contains(dep)) continue;
            for (String dependent : e.getValue()) {
                if (remaining.contains(dependent)) {
                    deps.get(dependent).add(dep);
                }
            }
        }

        String start = remaining.iterator().next();
        List<String> path = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String cur = start;
        while (cur != null && !seen.contains(cur)) {
            path.add(cur);
            seen.add(cur);
            Set<String> curDeps = deps.get(cur);
            cur = curDeps == null || curDeps.isEmpty() ? null : curDeps.iterator().next();
        }
        if (cur != null) {
            int loopStart = path.indexOf(cur);
            List<String> loop = new ArrayList<>(path.subList(loopStart, path.size()));
            loop.add(cur);
            return String.join(" -> ", loop);
        }
        return String.join(" -> ", path);
    }
}
