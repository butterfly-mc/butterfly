package net.butterfly.api.plugin;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

/**
 * Plugin metadata, typically parsed from a {@code plugin.yml} resource at the
 * root of the plugin jar.
 *
 * <p>The required fields are {@code name}, {@code version}, and
 * {@code mainClass}. All list fields are non-null; missing optional fields
 * default to an empty immutable list.
 *
 * @param name            plugin name (required)
 * @param version         plugin version (required)
 * @param mainClass       fully-qualified main class extending {@link Plugin} (required)
 * @param description     short human-readable description; never null
 * @param authors         author names; never null, may be empty
 * @param dependencies    hard dependencies (plugin names); never null
 * @param softDependencies soft dependencies; never null
 */
public record PluginManifest(
        String name,
        String version,
        String mainClass,
        String description,
        List<String> authors,
        List<String> dependencies,
        List<String> softDependencies) {

    /**
     * Compact constructor: validate required fields and harden list fields
     * to immutable, non-null lists.
     */
    public PluginManifest {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("plugin manifest: 'name' is required");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("plugin manifest: 'version' is required");
        }
        if (mainClass == null || mainClass.isBlank()) {
            throw new IllegalArgumentException("plugin manifest: 'main' is required");
        }
        description = description == null ? "" : description;
        authors = authors == null ? List.of() : List.copyOf(authors);
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        softDependencies = softDependencies == null ? List.of() : List.copyOf(softDependencies);
    }

    /**
     * Parse a manifest from a YAML stream.
     *
     * <p>Expected YAML keys:
     * <ul>
     *   <li>{@code name} (required)</li>
     *   <li>{@code version} (required)</li>
     *   <li>{@code main} (required) — fully-qualified main class</li>
     *   <li>{@code description} (optional)</li>
     *   <li>{@code authors} (optional, list of strings)</li>
     *   <li>{@code dependencies} (optional, list of strings)</li>
     *   <li>{@code soft-dependencies} (optional, list of strings)</li>
     * </ul>
     *
     * @throws IllegalArgumentException if the YAML is malformed, the root is
     *                                  not a mapping, or any required field
     *                                  is missing
     */
    public static PluginManifest fromYaml(InputStream yamlStream) {
        if (yamlStream == null) {
            throw new IllegalArgumentException("plugin manifest: yaml stream is null");
        }
        LoadSettings settings = LoadSettings.builder().setLabel("plugin.yml").build();
        Load load = new Load(settings);
        Object root = load.loadFromInputStream(yamlStream);
        if (root == null) {
            throw new IllegalArgumentException("plugin manifest: yaml document is empty");
        }
        if (!(root instanceof Map<?, ?> rawMap)) {
            throw new IllegalArgumentException(
                    "plugin manifest: yaml root must be a mapping, got " + root.getClass().getSimpleName());
        }

        String name = requireString(rawMap, "name");
        String version = requireString(rawMap, "version");
        String main = requireString(rawMap, "main");
        String description = optionalString(rawMap, "description", "");
        List<String> authors = optionalStringList(rawMap, "authors");
        List<String> dependencies = optionalStringList(rawMap, "dependencies");
        List<String> softDependencies = optionalStringList(rawMap, "soft-dependencies");

        return new PluginManifest(name, version, main, description, authors, dependencies, softDependencies);
    }

    private static String requireString(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException("plugin manifest: missing required field '" + key + "'");
        }
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException(
                    "plugin manifest: field '" + key + "' must be a string, got " + value.getClass().getSimpleName());
        }
        if (s.isBlank()) {
            throw new IllegalArgumentException("plugin manifest: field '" + key + "' must not be blank");
        }
        return s;
    }

    private static String optionalString(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException(
                    "plugin manifest: field '" + key + "' must be a string, got " + value.getClass().getSimpleName());
        }
        return s;
    }

    private static List<String> optionalStringList(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return Collections.emptyList();
        }
        if (!(value instanceof List<?> rawList)) {
            throw new IllegalArgumentException(
                    "plugin manifest: field '" + key + "' must be a list, got " + value.getClass().getSimpleName());
        }
        List<String> out = new ArrayList<>(rawList.size());
        for (Object element : rawList) {
            if (element == null) {
                throw new IllegalArgumentException(
                        "plugin manifest: field '" + key + "' contains a null entry");
            }
            if (!(element instanceof String s)) {
                throw new IllegalArgumentException(
                        "plugin manifest: field '" + key + "' must contain only strings, got "
                                + element.getClass().getSimpleName());
            }
            out.add(s);
        }
        return List.copyOf(out);
    }
}
