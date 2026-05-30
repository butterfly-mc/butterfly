package net.butterfly.core.tick;

/**
 * A network peer, viewed by the {@link PhasedPipeline} as four call-points.
 *
 * <p>The pipeline orchestrates these in three phases per tick:
 * <ol>
 *   <li>{@link #drainInbox()} — called on a decode worker (parallel across peers).</li>
 *   <li>{@link #simulate(byte[])} — called on the tick thread, once per inbound batch.</li>
 *   <li>{@link #drainOutbox()} + {@link #encodeAndSend(byte[])} — called on an encode worker
 *       (parallel across peers).</li>
 * </ol>
 *
 * <p>Implementations should never block the tick thread inside {@link #simulate(byte[])};
 * heavy work should be queued via the {@link net.butterfly.api.async.Scheduler}.
 */
public interface PeerHandle {

    /**
     * Returns the inbound batches accumulated since the last drain, fully decrypted and
     * decompressed (i.e. plaintext wire bytes ready to dispatch to packet handlers).
     * Implementations should clear their inbox before returning.
     *
     * @return zero or more plaintext batches; may be empty but not {@code null}
     */
    byte[][] drainInbox();

    /**
     * Process a single decoded batch on the tick thread. Mutations to world state are
     * permitted here.
     *
     * @param batchPlain plaintext wire bytes for one batch
     */
    void simulate(byte[] batchPlain);

    /**
     * Returns plaintext outbound batches the session wants to send this tick. Implementations
     * should clear their outbox before returning.
     *
     * @return zero or more plaintext batches; may be empty but not {@code null}
     */
    byte[][] drainOutbox();

    /**
     * Encode (compress + encrypt) and send a plaintext batch. Called on an encode worker
     * thread; implementations are expected to perform any I/O here without touching world state.
     *
     * @param batchPlain plaintext wire bytes for one batch
     */
    void encodeAndSend(byte[] batchPlain);
}
