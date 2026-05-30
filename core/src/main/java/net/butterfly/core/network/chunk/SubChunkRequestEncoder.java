package net.butterfly.core.network.chunk;

/**
 * Placeholder for the SubChunkRequest-mode encoder.
 *
 * <p>Bedrock 1.18+ supports a "request" send mode: the server first sends a
 * {@code LevelChunk} with {@code subChunkCount = SubChunkRequestModeLimited}
 * (or {@code Limitless}) carrying only biomes + border, and the client follows
 * up with {@code SubChunkRequest} packets the server answers with
 * {@code SubChunk} packets.
 *
 * <p>The MVP currently always uses the non-request, full-send path implemented
 * by {@link LevelChunkEncoder}. This class will hold the request-mode encoder
 * once we add it.
 */
public final class SubChunkRequestEncoder {

    private SubChunkRequestEncoder() {}

    // TODO(butterfly): implement SubChunkRequest-mode encoding once the MVP
    // full-chunk send path is verified against a real Bedrock client.
}
