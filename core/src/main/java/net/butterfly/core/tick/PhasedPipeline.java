package net.butterfly.core.tick;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * Three-phase tick pipeline that drives {@link PeerHandle}s:
 *
 * <pre>
 * DECODE phase     SIMULATE phase     ENCODE phase
 * (parallel)       (single thread)    (parallel)
 * ~5ms             ~30ms              ~10ms
 * decodeExecutor   tick thread        encodeExecutor
 * </pre>
 *
 * <p>Decode runs in parallel: each peer's {@code drainInbox} is dispatched to
 * {@code decodeExecutor}. Simulate is sequential — every decoded batch is fed back into
 * its peer's {@code simulate} on the tick thread. Encode again parallelises: each peer
 * drains its outbox, then encodes+sends each batch on {@code encodeExecutor}.
 *
 * <p>Wire it up with {@link #installInto(TickLoop)}: decode + simulate become the
 * loop's pre-tick hook, encode becomes the post-tick hook.
 */
public final class PhasedPipeline {

    private static final Logger LOG = LoggerFactory.getLogger(PhasedPipeline.class);

    private final ExecutorService decodeExecutor;
    private final ExecutorService encodeExecutor;
    private final Supplier<Collection<PeerHandle>> peers;

    /**
     * Decoded batches captured during the decode phase, consumed by the simulate phase.
     * Updated only on the tick thread (decode phase blocks on allOf before returning).
     */
    private List<Map.Entry<PeerHandle, byte[][]>> decodedThisTick = List.of();

    public PhasedPipeline(ExecutorService decodeExecutor,
                          ExecutorService encodeExecutor,
                          Supplier<Collection<PeerHandle>> peers) {
        this.decodeExecutor = Objects.requireNonNull(decodeExecutor, "decodeExecutor");
        this.encodeExecutor = Objects.requireNonNull(encodeExecutor, "encodeExecutor");
        this.peers = Objects.requireNonNull(peers, "peers");
    }

    /**
     * Decode phase: dispatch {@code drainInbox} for each peer in parallel and wait for all
     * to finish. Result is held in {@link #decodedThisTick} for the simulate phase.
     */
    public void decodePhase() {
        Collection<PeerHandle> snapshot = peers.get();
        if (snapshot == null || snapshot.isEmpty()) {
            decodedThisTick = List.of();
            return;
        }
        List<CompletableFuture<Map.Entry<PeerHandle, byte[][]>>> futures = new ArrayList<>(snapshot.size());
        for (PeerHandle peer : snapshot) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    byte[][] batches = peer.drainInbox();
                    return new AbstractMap.SimpleEntry<>(peer, batches != null ? batches : new byte[0][]);
                } catch (Throwable t) {
                    LOG.error("Decode failed for peer {}", peer, t);
                    return new AbstractMap.SimpleEntry<>(peer, new byte[0][]);
                }
            }, decodeExecutor));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        List<Map.Entry<PeerHandle, byte[][]>> result = new ArrayList<>(futures.size());
        for (CompletableFuture<Map.Entry<PeerHandle, byte[][]>> f : futures) {
            result.add(f.join());
        }
        decodedThisTick = result;
    }

    /**
     * Simulate phase: feed each captured batch into its peer's {@code simulate} on the
     * caller's thread (expected to be the tick thread).
     */
    public void simulatePhase() {
        for (Map.Entry<PeerHandle, byte[][]> entry : decodedThisTick) {
            PeerHandle peer = entry.getKey();
            for (byte[] batch : entry.getValue()) {
                try {
                    peer.simulate(batch);
                } catch (Throwable t) {
                    LOG.error("Simulate failed for peer {}", peer, t);
                }
            }
        }
        decodedThisTick = List.of();
    }

    /**
     * Encode phase: drain each peer's outbox and encode+send every batch in parallel.
     * Blocks until all peers' batches have been dispatched to the executor and returned.
     */
    public void encodePhase() {
        Collection<PeerHandle> snapshot = peers.get();
        if (snapshot == null || snapshot.isEmpty()) return;
        List<CompletableFuture<Void>> futures = new ArrayList<>(snapshot.size());
        for (PeerHandle peer : snapshot) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    byte[][] batches = peer.drainOutbox();
                    if (batches == null) return;
                    for (byte[] batch : batches) {
                        try {
                            peer.encodeAndSend(batch);
                        } catch (Throwable t) {
                            LOG.error("Encode-and-send failed for peer {}", peer, t);
                        }
                    }
                } catch (Throwable t) {
                    LOG.error("Encode phase failed for peer {}", peer, t);
                }
            }, encodeExecutor));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    /**
     * Convenience: a single tick of all three phases. Useful in tests; in production the
     * loop drives them via {@link #installInto(TickLoop)}.
     */
    public void runOnce() {
        decodePhase();
        simulatePhase();
        encodePhase();
    }

    /**
     * Wires this pipeline into a {@link TickLoop}: decode + simulate become the pre-tick
     * hook, encode becomes the post-tick hook.
     */
    public void installInto(TickLoop loop) {
        Objects.requireNonNull(loop, "loop");
        loop.setPreTickHook(() -> {
            decodePhase();
            simulatePhase();
        });
        loop.setPostTickHook(this::encodePhase);
    }
}
