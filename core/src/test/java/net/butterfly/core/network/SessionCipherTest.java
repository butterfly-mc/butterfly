package net.butterfly.core.network;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionCipherTest {

    /** With both compression and encryption on, wrap → unwrap round-trips a plaintext. */
    @Test
    void compressionAndEncryptionRoundTrip() {
        byte[] key = randomKey();
        SessionCipher cipher = new SessionCipher();
        cipher.enableCompression(0, 1);
        cipher.enableEncryption(key);

        byte[] plain = "Butterfly login flow handler test.".getBytes();
        byte[] wire = cipher.wrap(plain);
        // Same cipher unwraps because encrypt and decrypt counters live in separate PacketCipher instances.
        byte[] back = cipher.unwrap(wire);
        assertArrayEquals(plain, back);
    }

    /** With compression on but encryption off, wrap output starts with the algorithm header byte. */
    @Test
    void compressionOnlyHeaderByte() {
        SessionCipher cipher = new SessionCipher();
        cipher.enableCompression(0, 1);

        byte[] plain = "abc".getBytes();
        byte[] wire = cipher.wrap(plain);
        // First byte is the compression header — for ZLIB it's 0x00.
        assertEquals(0x00, wire[0] & 0xFF, "expected zlib header byte at offset 0");
    }

    /**
     * Two separate cipher instances sharing a key and matching counters: server-side wraps,
     * client-side unwraps. Sequential packets line up because each side keeps its own
     * encrypt and decrypt counter on the same key — so server-encrypt-counter aligns with
     * client-decrypt-counter, packet by packet.
     */
    @Test
    void serverEncryptClientDecryptSequential() {
        byte[] key = randomKey();
        SessionCipher server = new SessionCipher();
        SessionCipher client = new SessionCipher();
        server.enableCompression(0, 1);
        client.enableCompression(0, 1);
        server.enableEncryption(key);
        client.enableEncryption(key);

        byte[] p1 = "first".getBytes();
        byte[] p2 = "second".getBytes();
        byte[] p3 = "third".getBytes();

        byte[] w1 = server.wrap(p1);
        byte[] w2 = server.wrap(p2);
        byte[] w3 = server.wrap(p3);

        // Client must process them in order — CTR counters and SHA checksums depend on it.
        assertArrayEquals(p1, client.unwrap(w1));
        assertArrayEquals(p2, client.unwrap(w2));
        assertArrayEquals(p3, client.unwrap(w3));
    }

    /** Without enabling either feature, wrap and unwrap pass through identity. */
    @Test
    void passthroughWhenDisabled() {
        SessionCipher cipher = new SessionCipher();
        byte[] plain = new byte[]{1, 2, 3, 4, 5};
        assertArrayEquals(plain, cipher.unwrap(cipher.wrap(plain)));
    }

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }
}
