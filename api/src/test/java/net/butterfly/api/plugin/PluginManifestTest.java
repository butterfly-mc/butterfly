package net.butterfly.api.plugin;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginManifestTest {

    private static ByteArrayInputStream yaml(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parsesAllFields() {
        String yaml = """
                name: TestPlugin
                version: 1.2.3
                main: com.example.MyPlugin
                description: A test plugin
                authors:
                  - Alice
                  - Bob
                dependencies:
                  - CorePlugin
                soft-dependencies:
                  - OptionalPlugin
                """;

        PluginManifest manifest = PluginManifest.fromYaml(yaml(yaml));

        assertEquals("TestPlugin", manifest.name());
        assertEquals("1.2.3", manifest.version());
        assertEquals("com.example.MyPlugin", manifest.mainClass());
        assertEquals("A test plugin", manifest.description());
        assertEquals(java.util.List.of("Alice", "Bob"), manifest.authors());
        assertEquals(java.util.List.of("CorePlugin"), manifest.dependencies());
        assertEquals(java.util.List.of("OptionalPlugin"), manifest.softDependencies());
    }

    @Test
    void optionalFieldsDefaultToEmpty() {
        String yaml = """
                name: Bare
                version: 0.1.0
                main: com.example.Bare
                """;

        PluginManifest manifest = PluginManifest.fromYaml(yaml(yaml));

        assertEquals("", manifest.description());
        assertTrue(manifest.authors().isEmpty(), "authors should default to empty list");
        assertTrue(manifest.dependencies().isEmpty(), "dependencies should default to empty list");
        assertTrue(manifest.softDependencies().isEmpty(), "soft-dependencies should default to empty list");
    }

    @Test
    void missingNameThrowsWithMessage() {
        String yaml = """
                version: 1.0.0
                main: com.example.NoName
                """;

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> PluginManifest.fromYaml(yaml(yaml)));
        assertTrue(ex.getMessage().contains("name"), () -> "message should mention 'name': " + ex.getMessage());
    }

    @Test
    void missingVersionThrowsWithMessage() {
        String yaml = """
                name: NoVersion
                main: com.example.NoVersion
                """;

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> PluginManifest.fromYaml(yaml(yaml)));
        assertTrue(ex.getMessage().contains("version"), () -> "message should mention 'version': " + ex.getMessage());
    }

    @Test
    void missingMainThrowsWithMessage() {
        String yaml = """
                name: NoMain
                version: 1.0.0
                """;

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> PluginManifest.fromYaml(yaml(yaml)));
        assertTrue(ex.getMessage().contains("main"), () -> "message should mention 'main': " + ex.getMessage());
    }

    @Test
    void emptyDocumentThrows() {
        assertThrows(IllegalArgumentException.class, () -> PluginManifest.fromYaml(yaml("")));
    }

    @Test
    void scalarRootThrows() {
        assertThrows(IllegalArgumentException.class, () -> PluginManifest.fromYaml(yaml("just-a-string")));
    }

    @Test
    void nonStringListEntryThrows() {
        String yaml = """
                name: BadAuthors
                version: 1.0.0
                main: com.example.Plugin
                authors:
                  - 42
                """;

        assertThrows(IllegalArgumentException.class, () -> PluginManifest.fromYaml(yaml(yaml)));
    }

    @Test
    void authorsListIsImmutable() {
        String yaml = """
                name: Imm
                version: 1.0.0
                main: com.example.Plugin
                authors: [Alice]
                """;

        PluginManifest manifest = PluginManifest.fromYaml(yaml(yaml));
        assertThrows(UnsupportedOperationException.class, () -> manifest.authors().add("Mallory"));
    }
}
