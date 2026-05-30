package net.butterfly.core.plugin;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.butterfly.api.plugin.PluginManifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginLoaderSortTest {

    private static PluginManifest manifest(String name, List<String> deps, List<String> softDeps) {
        return new PluginManifest(
                name,
                "1.0.0",
                "com.example." + name,
                "",
                List.of(),
                deps,
                softDeps);
    }

    @Test
    void hardAndSoftDependenciesProduceLoadOrder() {
        PluginManifest a = manifest("A", List.of(), List.of());
        PluginManifest b = manifest("B", List.of("A"), List.of());
        PluginManifest c = manifest("C", List.of(), List.of("B"));

        // Provide them in a "wrong" order to make sure sort actually does work.
        List<PluginManifest> sorted = PluginLoader.sortByDependencies(List.of(c, b, a));

        assertEquals(List.of("A", "B", "C"),
                sorted.stream().map(PluginManifest::name).toList(),
                "A must precede B (hard dep), B must precede C (soft dep present)");
    }

    @Test
    void softDependencyToMissingPluginIsIgnored() {
        // C softly depends on B, but B isn't in the input. Should not error.
        PluginManifest a = manifest("A", List.of(), List.of());
        PluginManifest c = manifest("C", List.of(), List.of("B"));

        List<PluginManifest> sorted = PluginLoader.sortByDependencies(List.of(a, c));

        assertEquals(List.of("A", "C"),
                sorted.stream().map(PluginManifest::name).toList());
    }

    @Test
    void missingHardDependencyThrows() {
        PluginManifest a = manifest("A", List.of("Missing"), List.of());

        PluginLoadException ex = assertThrows(
                PluginLoadException.class,
                () -> PluginLoader.sortByDependencies(List.of(a)));
        assertTrue(ex.getMessage().contains("Missing"),
                () -> "message should name the missing dep: " + ex.getMessage());
    }

    @Test
    void cycleThrowsWithCycleDescription() {
        PluginManifest a = manifest("A", List.of("B"), List.of());
        PluginManifest b = manifest("B", List.of("A"), List.of());

        PluginLoadException ex = assertThrows(
                PluginLoadException.class,
                () -> PluginLoader.sortByDependencies(List.of(a, b)));
        assertTrue(ex.getMessage().startsWith("dependency cycle:"),
                () -> "message should start with 'dependency cycle:': " + ex.getMessage());
        assertTrue(ex.getMessage().contains("A") && ex.getMessage().contains("B"),
                () -> "message should mention both nodes: " + ex.getMessage());
    }

    @Test
    void duplicateNamesThrow() {
        PluginManifest a = manifest("Same", List.of(), List.of());
        PluginManifest b = manifest("Same", List.of(), List.of());

        assertThrows(PluginLoadException.class,
                () -> PluginLoader.sortByDependencies(List.of(a, b)));
    }
}
