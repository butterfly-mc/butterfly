package net.butterfly.core.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.butterfly.crypto.EcdhKeyExchange;
import net.butterfly.nbt.VarInts;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.lang.JoseException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoginParserTest {

    /** Build a fake legacy Login body with one self-signed JWT. Parser must extract identity. */
    @Test
    void parseLegacyChain() {
        KeyPair clientKey = EcdhKeyExchange.generateKeyPair();
        String pub = EcdhKeyExchange.encodePublicKey(clientKey.getPublic());

        Map<String, Object> extraData = new LinkedHashMap<>();
        extraData.put("XUID", "1234567890");
        extraData.put("identity", "00000000-0000-0000-0000-000000000001");
        extraData.put("displayName", "Steve");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("identityPublicKey", pub);
        body.put("extraData", extraData);

        String token = signJwt(clientKey, pub, body);

        JsonObject root = new JsonObject();
        JsonArray chain = new JsonArray();
        chain.add(token);
        root.add("chain", chain);
        String chainJson = root.toString();

        byte[] login = encodeLoginBody(975, chainJson, "");
        LoginParser.Identity id = LoginParser.parse(login);
        assertEquals(975, id.clientProtocol());
        assertEquals("Steve", id.displayName());
        assertEquals("1234567890", id.xuid());
        assertNotNull(id.clientIdentityKey());
    }

    /** Build a fake OIDC Login body — single Token JWT with cpk/xid/xname claims. */
    @Test
    void parseOidcChain() {
        KeyPair clientKey = EcdhKeyExchange.generateKeyPair();
        String pub = EcdhKeyExchange.encodePublicKey(clientKey.getPublic());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cpk", pub);
        body.put("xid", "9876543210");
        body.put("xname", "Alex");

        String token = signJwt(clientKey, pub, body);

        JsonObject root = new JsonObject();
        root.addProperty("AuthenticationType", 0);
        root.addProperty("Token", token);
        String chainJson = root.toString();

        byte[] login = encodeLoginBody(975, chainJson, "");
        LoginParser.Identity id = LoginParser.parse(login);
        assertEquals(975, id.clientProtocol());
        assertEquals("Alex", id.displayName());
        assertEquals("9876543210", id.xuid());
        assertNotNull(id.clientIdentityKey());
    }

    /** Malformed: neither {chain:[...]} nor {Token,AuthenticationType} → IllegalArgumentException. */
    @Test
    void parseMalformedThrows() {
        String chainJson = "{\"unrelated\":42}";
        byte[] login = encodeLoginBody(975, chainJson, "");
        assertThrows(IllegalArgumentException.class, () -> LoginParser.parse(login));
    }

    /** Malformed JSON entirely → IllegalArgumentException. */
    @Test
    void parseInvalidJsonThrows() {
        byte[] login = encodeLoginBody(975, "not-json", "");
        assertThrows(IllegalArgumentException.class, () -> LoginParser.parse(login));
    }

    // ---- helpers ----

    private static String signJwt(KeyPair signer, String x5u, Map<String, Object> body) {
        try {
            JsonWebSignature jws = new JsonWebSignature();
            jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384);
            jws.setHeader("x5u", x5u);
            jws.setPayload(toJson(body));
            jws.setKey(signer.getPrivate());
            return jws.getCompactSerialization();
        } catch (JoseException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static String toJson(Map<String, Object> body) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : body.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('\"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof Map) sb.append(toJson((Map<String, Object>) v));
            else if (v instanceof Number) sb.append(v);
            else sb.append('\"').append(v.toString().replace("\"", "\\\"")).append('\"');
        }
        sb.append('}');
        return sb.toString();
    }

    /** Encode a Login body matching what BatchCodec would deliver after stripping the packet id. */
    static byte[] encodeLoginBody(int clientProtocol, String chainJson, String clientDataJwt) {
        byte[] chainBytes = chainJson.getBytes(StandardCharsets.UTF_8);
        byte[] dataBytes = clientDataJwt.getBytes(StandardCharsets.UTF_8);
        ByteBuf conn = Unpooled.buffer();
        conn.writeIntLE(chainBytes.length);
        conn.writeBytes(chainBytes);
        conn.writeIntLE(dataBytes.length);
        conn.writeBytes(dataBytes);

        ByteBuf out = Unpooled.buffer();
        out.writeInt(clientProtocol);
        VarInts.writeUnsignedInt(out, conn.readableBytes());
        out.writeBytes(conn);
        conn.release();

        byte[] result = new byte[out.readableBytes()];
        out.readBytes(result);
        out.release();
        return result;
    }
}
