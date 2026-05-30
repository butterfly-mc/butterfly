package net.butterfly.core.tick;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhasedPipelineTest {

    private ExecutorService decode;
    private ExecutorService encode;

    @AfterEach
    void tearDown() {
        if (decode != null) decode.shutdownNow();
        if (encode != null) encode.shutdownNow();
    }

    @Test
    void runOnce_invokesPhasesInOrder() throws Exception {
        decode = Executors.newSingleThreadExecutor(r -> new Thread(r, "decode"));
        encode = Executors.newSingleThreadExecutor(r -> new Thread(r, "encode"));

        RecordingPeer peer = new RecordingPeer(new byte[][]{{1, 2, 3}}, new byte[][]{{9, 9}});
        PhasedPipeline pipeline = new PhasedPipeline(decode, encode, () -> List.of(peer));

        pipeline.runOnce();

        // Order: drainInbox → simulate → drainOutbox → encodeAndSend
        assertEquals(List.of("drainInbox", "simulate", "drainOutbox", "encodeAndSend"), peer.calls);
    }

    @Test
    void multiplePeers_decodeAndEncodeRunConcurrently() throws Exception {
        // Two-thread pools so both peers can run in parallel.
        decode = Executors.newFixedThreadPool(2, r -> new Thread(r, "decode-pool"));
        encode = Executors.newFixedThreadPool(2, r -> new Thread(r, "encode-pool"));

        CountDownLatch decodeEntered = new CountDownLatch(2);
        CountDownLatch decodeRelease = new CountDownLatch(1);
        CountDownLatch encodeEntered = new CountDownLatch(2);
        CountDownLatch encodeRelease = new CountDownLatch(1);

        LatchPeer p1 = new LatchPeer(decodeEntered, decodeRelease, encodeEntered, encodeRelease);
        LatchPeer p2 = new LatchPeer(decodeEntered, decodeRelease, encodeEntered, encodeRelease);
        Collection<PeerHandle> peers = List.of(p1, p2);
        PhasedPipeline pipeline = new PhasedPipeline(decode, encode, () -> peers);

        // Decode phase on a worker so we can release the latch from the main thread.
        Thread decoder = new Thread(pipeline::decodePhase, "decoder");
        decoder.start();
        assertTrue(decodeEntered.await(2, TimeUnit.SECONDS), "both peers must enter decode in parallel");
        decodeRelease.countDown();
        decoder.join(2_000L);

        pipeline.simulatePhase();

        Thread encoder = new Thread(pipeline::encodePhase, "encoder");
        encoder.start();
        assertTrue(encodeEntered.await(2, TimeUnit.SECONDS), "both peers must enter encode in parallel");
        encodeRelease.countDown();
        encoder.join(2_000L);
    }

    /** A peer that records every interaction in order. */
    private static final class RecordingPeer implements PeerHandle {
        final List<String> calls = new ArrayList<>();
        private final byte[][] inbound;
        private final byte[][] outbound;

        RecordingPeer(byte[][] inbound, byte[][] outbound) {
            this.inbound = inbound;
            this.outbound = outbound;
        }

        @Override
        public synchronized byte[][] drainInbox() {
            calls.add("drainInbox");
            return inbound;
        }

        @Override
        public synchronized void simulate(byte[] batchPlain) {
            calls.add("simulate");
        }

        @Override
        public synchronized byte[][] drainOutbox() {
            calls.add("drainOutbox");
            return outbound;
        }

        @Override
        public synchronized void encodeAndSend(byte[] batchPlain) {
            calls.add("encodeAndSend");
        }
    }

    /** A peer that latches inside drainInbox / encodeAndSend so we can verify parallel entry. */
    private static final class LatchPeer implements PeerHandle {
        private final CountDownLatch decodeEntered;
        private final CountDownLatch decodeRelease;
        private final CountDownLatch encodeEntered;
        private final CountDownLatch encodeRelease;

        LatchPeer(CountDownLatch decodeEntered, CountDownLatch decodeRelease,
                  CountDownLatch encodeEntered, CountDownLatch encodeRelease) {
            this.decodeEntered = decodeEntered;
            this.decodeRelease = decodeRelease;
            this.encodeEntered = encodeEntered;
            this.encodeRelease = encodeRelease;
        }

        @Override
        public byte[][] drainInbox() {
            decodeEntered.countDown();
            try {
                decodeRelease.await();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return new byte[0][];
        }

        @Override
        public void simulate(byte[] batchPlain) { /* never called — empty inbox */ }

        @Override
        public byte[][] drainOutbox() {
            return new byte[][]{{0}};
        }

        @Override
        public void encodeAndSend(byte[] batchPlain) {
            encodeEntered.countDown();
            try {
                encodeRelease.await();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
