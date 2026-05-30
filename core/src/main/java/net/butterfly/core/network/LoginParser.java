package net.butterfly.core.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.butterfly.crypto.EcdhKeyExchange;
import net.butterfly.nbt.VarInts;
import org.jose4j.json.JsonUtil;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.lang.JoseException;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Map;

/**
 * Parses a Bedrock {@code Login} (0x01) packet body and extracts the player identity.
 *
 * <p>Body layout (protocol 975):
 * <pre>
 *   int32 BE   clientProtocol
 *   varint     connectionRequestLength
 *   bytes      connectionRequest of that length:
 *       int32 LE  chainJsonLength
 *       bytes     chainJson         ({"chain":["jwt1","jwt2",...]} or {"AuthenticationType":...,"Token":"..."})
 *       int32 LE  clientDataJwtLength
 *       bytes     clientDataJwt
 * </pre>
 *
 * <p>The chain JSON has two known shapes:
 * <ul>
 *   <li><b>Legacy</b>: {@code {"chain":[jwt1, jwt2, ...]}} — the leaf token's
 *       {@code identityPublicKey} is the client's signing key, and {@code extraData}
 *       carries {@code XUID}, {@code identity}, and {@code displayName}.</li>
 *   <li><b>OIDC (modern)</b>: {@code {"AuthenticationType":N, "Token":"<JWT>"}} — the
 *       single token's body has {@code cpk} (client public key, SPKI base64),
 *       {@code xid} (XUID) and {@code xname} (display name).</li>
 * </ul>
 *
 * <p>JWT signatures are NOT verified here (this is the MVP server: trust whatever the
 * client sends). For production hardening, route through
 * {@link net.butterfly.crypto.LoginChain#verify(java.util.List)} once a signed-chain
 * verification path is available.
 */
public final class LoginParser {
    private LoginParser() {}

    /** Identity extracted from a Login chain. {@code clientIdentityKey} is the ECDH peer used for handshake. */
    public record Identity(int clientProtocol, String displayName, String xuid, PublicKey clientIdentityKey) {}

    /** Parse a Login body (without the packet-id header that {@code BatchCodec} already stripped). */
    public static Identity parse(byte[] body) {
        ByteBuf buf = Unpooled.wrappedBuffer(body);
        return parse(buf);
    }

    /** Parse a Login body from a ByteBuf — caller retains ownership; reader index is advanced. */
    public static Identity parse(ByteBuf body) {
        int clientProtocol = body.readInt();    // BE
        int connReqLen = (int) VarInts.readUnsignedInt(body);
        ByteBuf conn = body.readSlice(connReqLen);

        int chainLen = conn.readIntLE();
        byte[] chainBytes = new byte[chainLen];
        conn.readBytes(chainBytes);

        int clientDataLen = conn.readIntLE();
        byte[] clientDataBytes = new byte[clientDataLen];
        conn.readBytes(clientDataBytes);

        String chainJson = new String(chainBytes, StandardCharsets.UTF_8);
        return extract(clientProtocol, chainJson);
    }

    @SuppressWarnings("unchecked")
    private static Identity extract(int clientProtocol, String chainJson) {
        JsonObject root;
        try {
            root = JsonParser.parseString(chainJson).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("login chain is not valid JSON", e);
        }

        // Format A — modern OIDC: { "AuthenticationType": N, "Token": "<JWT>" }
        if (root.has("Token") && root.has("AuthenticationType")) {
            String token = root.get("Token").getAsString();
            Map<String, Object> body = unverifiedPayload(token);
            String pub = str(body.get("cpk"));
            if (pub.isEmpty())
                throw new IllegalArgumentException("OIDC login token missing 'cpk' (client public key)");
            return new Identity(
                clientProtocol,
                str(body.get("xname")),
                str(body.get("xid")),
                EcdhKeyExchange.decodePublicKey(pub));
        }

        // Format B — legacy chain: { "chain": [...] }
        if (root.has("chain")) {
            JsonArray chain = root.getAsJsonArray("chain");
            if (chain == null || chain.isEmpty())
                throw new IllegalArgumentException("legacy login chain is empty");
            String lastToken = chain.get(chain.size() - 1).getAsString();
            Map<String, Object> body = unverifiedPayload(lastToken);
            String pub = str(body.get("identityPublicKey"));
            if (pub.isEmpty())
                throw new IllegalArgumentException("legacy login leaf missing 'identityPublicKey'");
            Object extra = body.get("extraData");
            String displayName = "";
            String xuid = "";
            if (extra instanceof Map) {
                Map<String, Object> data = (Map<String, Object>) extra;
                displayName = str(data.get("displayName"));
                xuid = str(data.get("XUID"));
            }
            return new Identity(
                clientProtocol, displayName, xuid, EcdhKeyExchange.decodePublicKey(pub));
        }

        throw new IllegalArgumentException(
            "login chain is neither legacy ({chain:[...]}) nor OIDC ({Token,AuthenticationType}); keys=" + root.keySet());
    }

    private static Map<String, Object> unverifiedPayload(String token) {
        try {
            JsonWebSignature jws = new JsonWebSignature();
            jws.setCompactSerialization(token);
            jws.setDoKeyValidation(false);
            return JsonUtil.parseJson(jws.getUnverifiedPayload());
        } catch (JoseException e) {
            throw new IllegalArgumentException("malformed login JWT: " + e.getMessage(), e);
        }
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }
}
