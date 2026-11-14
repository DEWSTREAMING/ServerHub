package com.serverhub.transfer;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.serverhub.config.HubConfig;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Issues and verifies one-time HMAC-SHA256 transfer cookies so vanilla clients
 * transferring between Hub and backends do not need to re-hit Mojang's session
 * servers, while still preserving Mojang authentication for direct joins.
 */
public final class TransferAuthToken {
    private static final Logger LOGGER = LoggerFactory.getLogger(TransferAuthToken.class);
    public static final Identifier COOKIE_KEY =
            Identifier.fromNamespaceAndPath("serverhub", "transfer_token");

    private static final byte VERSION = 1;
    private static final int HMAC_BYTES = 32;
    private static final int MAX_COOKIE_BYTES = 5120;
    private static final int MAX_BODY_WITH_TEXTURES = 4500;
    private static final long TOKEN_TTL_MS = 60_000L;
    private static final long MAX_FUTURE_SKEW_MS = 120_000L;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<UUID, Long> USED_NONCES = new ConcurrentHashMap<>();

    private TransferAuthToken() {}

    public static boolean isConfigured() {
        String secret = HubConfig.get().sharedSecret;
        return secret != null && !secret.isBlank();
    }

    @Nullable
    public static byte[] issue(GameProfile profile) {
        String secret = HubConfig.get().sharedSecret;
        if (secret == null || secret.isBlank()) {
            return null;
        }
        return issueWithSecret(profile, secret, System.currentTimeMillis() + TOKEN_TTL_MS);
    }

    @Nullable
    static byte[] issueWithSecret(GameProfile profile, String secret, long expiresAtMs) {
        Property textures = profile.properties().get("textures").stream().findFirst().orElse(null);
        UUID nonce = new UUID(RANDOM.nextLong(), RANDOM.nextLong());
        try {
            byte[] body = encodeBody(profile.id(), profile.name(), expiresAtMs, nonce, textures);
            if (body.length > MAX_BODY_WITH_TEXTURES && textures != null) {
                body = encodeBody(profile.id(), profile.name(), expiresAtMs, nonce, null);
            }
            byte[] mac = computeHmac(secret, body);
            byte[] token = new byte[body.length + mac.length];
            System.arraycopy(body, 0, token, 0, body.length);
            System.arraycopy(mac, 0, token, body.length, mac.length);
            if (token.length > MAX_COOKIE_BYTES) {
                return null;
            }
            return token;
        } catch (IOException | GeneralSecurityException e) {
            LOGGER.warn("Failed to issue transfer auth cookie for {}", profile.name(), e);
            return null;
        }
    }

    @Nullable
    public static GameProfile verifyAndConsume(@Nullable byte[] payload, @Nullable String expectedUsername) {
        String secret = HubConfig.get().sharedSecret;
        if (secret == null || secret.isBlank()) {
            return null;
        }
        return verifyAndConsumeWithSecret(payload, expectedUsername, secret, System.currentTimeMillis());
    }

    @Nullable
    static GameProfile verifyAndConsumeWithSecret(
            @Nullable byte[] payload,
            @Nullable String expectedUsername,
            String secret,
            long nowMs) {
        if (payload == null || expectedUsername == null || payload.length <= HMAC_BYTES || payload.length > MAX_COOKIE_BYTES) {
            return null;
        }
        int bodyLen = payload.length - HMAC_BYTES;
        byte[] body = Arrays.copyOfRange(payload, 0, bodyLen);
        byte[] providedMac = Arrays.copyOfRange(payload, bodyLen, payload.length);

        try {
            byte[] expectedMac = computeHmac(secret, body);
            if (!MessageDigest.isEqual(providedMac, expectedMac)) {
                return null;
            }
        } catch (GeneralSecurityException e) {
            LOGGER.warn("Failed to verify transfer auth cookie HMAC", e);
            return null;
        }

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
            byte version = in.readByte();
            if (version != VERSION) {
                return null;
            }
            UUID playerId = new UUID(in.readLong(), in.readLong());
            String username = in.readUTF();
            long expiresAtMs = in.readLong();
            UUID nonce = new UUID(in.readLong(), in.readLong());
            boolean hasTextures = in.readBoolean();
            String texValue = hasTextures ? in.readUTF() : null;
            String texSig = hasTextures && in.readBoolean() ? in.readUTF() : null;

            if (!username.equals(expectedUsername)) {
                return null;
            }
            if (nowMs > expiresAtMs || expiresAtMs > nowMs + MAX_FUTURE_SKEW_MS) {
                return null;
            }

            pruneExpiredNonces(nowMs);
            if (USED_NONCES.putIfAbsent(nonce, expiresAtMs) != null) {
                return null;
            }

            if (hasTextures && texValue != null) {
                Multimap<String, Property> props = LinkedHashMultimap.create();
                props.put("textures", new Property("textures", texValue, texSig));
                return new GameProfile(playerId, username, new PropertyMap(props));
            }
            return new GameProfile(playerId, username);
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] encodeBody(
            UUID playerId,
            String username,
            long expiresAtMs,
            UUID nonce,
            @Nullable Property textures) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeByte(VERSION);
            out.writeLong(playerId.getMostSignificantBits());
            out.writeLong(playerId.getLeastSignificantBits());
            out.writeUTF(username);
            out.writeLong(expiresAtMs);
            out.writeLong(nonce.getMostSignificantBits());
            out.writeLong(nonce.getLeastSignificantBits());
            if (textures != null) {
                out.writeBoolean(true);
                out.writeUTF(textures.value());
                if (textures.signature() != null) {
                    out.writeBoolean(true);
                    out.writeUTF(textures.signature());
                } else {
                    out.writeBoolean(false);
                }
            } else {
                out.writeBoolean(false);
            }
        }
        return baos.toByteArray();
    }

    private static byte[] computeHmac(String secret, byte[] body) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(body);
    }

    private static void pruneExpiredNonces(long nowMs) {
        if (USED_NONCES.size() > 64) {
            USED_NONCES.entrySet().removeIf(entry -> entry.getValue() < nowMs);
        }
    }
}
