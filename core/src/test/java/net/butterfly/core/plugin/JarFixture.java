package net.butterfly.core.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

/**
 * Builds a fake plugin jar on disk for tests. The jar contains a
 * {@code butterfly.yml} entry plus one or more compiled class files.
 */
final class JarFixture {

    private JarFixture() {}

    static Path buildPluginJar(Path dir, String jarName, String yaml,
                               ClassEntry... classEntries) throws IOException {
        Path jar = dir.resolve(jarName + ".jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            jos.putNextEntry(new ZipEntry(PluginLoader.MANIFEST_ENTRY));
            jos.write(yaml.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();

            for (ClassEntry entry : classEntries) {
                String classPath = entry.fqn().replace('.', '/') + ".class";
                jos.putNextEntry(new ZipEntry(classPath));
                jos.write(entry.bytes());
                jos.closeEntry();
            }
        }
        return jar;
    }

    /** Read the .class bytes for {@code clazz} off the test classpath. */
    static byte[] classBytes(Class<?> clazz) throws IOException {
        String resource = clazz.getSimpleName() + ".class";
        try (InputStream in = clazz.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("could not find resource " + resource + " for " + clazz.getName());
            }
            return in.readAllBytes();
        }
    }

    record ClassEntry(String fqn, byte[] bytes) {}

    static ClassEntry entry(Class<?> clazz) throws IOException {
        return new ClassEntry(clazz.getName(), classBytes(clazz));
    }
}
