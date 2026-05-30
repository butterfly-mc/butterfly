package net.butterfly.core.world;

import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.WriteBatch;
import org.iq80.leveldb.impl.Iq80DBFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Thin wrapper around an underlying LevelDB instance used to back a Bedrock world.
 *
 * <p>Instances are obtained via {@link #open(Path)} and must be {@link #close() closed}
 * when no longer needed.
 */
public final class LevelDb implements AutoCloseable {

    private final DB db;

    private LevelDb(DB db) {
        this.db = db;
    }

    /**
     * Opens (or creates) a LevelDB database at {@code dir}. The directory is created if it
     * does not already exist.
     */
    public static LevelDb open(Path dir) throws IOException {
        Files.createDirectories(dir);
        Options options = new Options().createIfMissing(true);
        DB db = Iq80DBFactory.factory.open(dir.toFile(), options);
        return new LevelDb(db);
    }

    /** Returns the value for {@code key}, or {@code null} if no entry exists. */
    public byte[] get(byte[] key) {
        return db.get(key);
    }

    public void put(byte[] key, byte[] value) {
        db.put(key, value);
    }

    public void delete(byte[] key) {
        db.delete(key);
    }

    /** Creates a new write batch; the caller is responsible for closing or applying it. */
    public WriteBatch batch() {
        return db.createWriteBatch();
    }

    /** Applies a previously-created write batch atomically. */
    public void write(WriteBatch batch) {
        db.write(batch);
    }

    /**
     * Returns an iterator over all key-value pairs in the database. The returned iterator
     * holds a snapshot of the underlying {@link DBIterator} and must be closed indirectly by
     * exhausting it or by closing this database.
     */
    public Iterator<Map.Entry<byte[], byte[]>> iterator() {
        DBIterator it = db.iterator();
        it.seekToFirst();
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public Map.Entry<byte[], byte[]> next() {
                if (!it.hasNext()) throw new NoSuchElementException();
                Map.Entry<byte[], byte[]> e = it.next();
                return new AbstractMap.SimpleImmutableEntry<>(e.getKey(), e.getValue());
            }
        };
    }

    @Override
    public void close() throws IOException {
        db.close();
    }
}
