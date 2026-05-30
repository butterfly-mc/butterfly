package net.butterfly.core.network;

import net.butterfly.codec.Compression;
import net.butterfly.crypto.PacketCipher;

/**
 * Per-session compression + encryption helper. Mirrors the proxy's {@code DirectionCipher}
 * but for one peer-pair side: it owns a paired (encrypt, decrypt) cipher built from a
 * shared key — each cipher has its own internal counter so wrap and unwrap progress
 * independently.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>Initially both compression and encryption are off — {@link #wrap(byte[])} and
 *       {@link #unwrap(byte[])} pass bytes through untouched.</li>
 *   <li>{@link #enableCompression(int, int)} flips compression on for both directions
 *       — typically called right after sending NetworkSettings.</li>
 *   <li>{@link #enableEncryption(byte[])} installs the AES-256-CTR ciphers — typically
 *       called right after sending the server-to-client handshake JWT, so the JWT itself
 *       still goes out unencrypted.</li>
 * </ul>
 *
 * <p>Because each {@link PacketCipher} keeps its own running CTR counter, the same
 * SessionCipher safely round-trips outbound and inbound traffic without resetting state.
 */
public final class SessionCipher {
    private boolean compressionEnabled;
    private Compression algorithm = Compression.ZLIB;
    private int threshold = 1;

    private PacketCipher encryptCipher;
    private PacketCipher decryptCipher;

    /** Mark compression as enabled with the algorithm + threshold from NetworkSettings. */
    public void enableCompression(int algorithmCode, int threshold) {
        if (algorithmCode == 1) this.algorithm = Compression.SNAPPY;
        else this.algorithm = Compression.ZLIB;        // 0 or unknown → zlib default
        this.threshold = Math.max(1, threshold);
        this.compressionEnabled = true;
    }

    public boolean compressionEnabled() { return compressionEnabled; }

    /**
     * Both ciphers share the same AES key, but use independent counters. Equivalent to
     * calling {@link #enableInboundEncryption(byte[])} then {@link #enableOutboundEncryption(byte[])}
     * — provided as a convenience when both directions flip together.
     */
    public void enableEncryption(byte[] key) {
        enableInboundEncryption(key);
        enableOutboundEncryption(key);
    }

    /** Install only the decrypt cipher — used when inbound flips before outbound. */
    public void enableInboundEncryption(byte[] key) {
        this.decryptCipher = new PacketCipher(key, false);
    }

    /** Install only the encrypt cipher — used when outbound flips after sending the handshake JWT. */
    public void enableOutboundEncryption(byte[] key) {
        this.encryptCipher = new PacketCipher(key, true);
    }

    public boolean encryptionEnabled() { return encryptCipher != null && decryptCipher != null; }
    public boolean outboundEncryptionEnabled() { return encryptCipher != null; }
    public boolean inboundEncryptionEnabled() { return decryptCipher != null; }

    /** Outbound: compress (if on) → encrypt (if on) → return wire bytes. */
    public byte[] wrap(byte[] plain) {
        byte[] compressed = compressionEnabled
            ? Compression.frame(plain, algorithm, threshold)
            : plain;
        return encryptCipher != null ? encryptCipher.process(compressed) : compressed;
    }

    /** Inbound: decrypt (if on) → decompress (if on) → return plaintext batch. */
    public byte[] unwrap(byte[] wire) {
        byte[] decrypted = decryptCipher != null ? decryptCipher.process(wire) : wire;
        return compressionEnabled ? Compression.unframe(decrypted) : decrypted;
    }
}
