package net.butterfly.core.network;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Smoke test — confirms ConnectionManager can be constructed without binding to a real
 * UDP port. Real network behaviour is exercised by the integration suite.
 */
class ConnectionManagerSmokeTest {

    @Test
    void constructorDoesNotThrow() {
        ConnectionManager.Motd motd = new ConnectionManager.Motd(
            "Butterfly", 975, "1.21.0", 0, 10, "world");
        AtomicReference<ClientSession> seen = new AtomicReference<>();
        ConnectionManager mgr = assertDoesNotThrow(() -> new ConnectionManager(motd, seen::set));
        assertNotNull(mgr);
        // stop() before start() should be a no-op.
        assertDoesNotThrow(mgr::stop);
    }
}
