package io.relayforge.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class WebhookSigner {
    private static final String ALGORITHM = "HmacSHA256";
    private static final String VERSION = "v1=";

    private WebhookSigner() {}

    public static String sign(byte[] secret, UUID messageId, Instant timestamp, byte[] exactPayload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.clone(), ALGORITHM));
            mac.update(signingPrefix(messageId, timestamp));
            mac.update(exactPayload);
            return VERSION + Base64.getEncoder().encodeToString(mac.doFinal());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", exception);
        }
    }

    public static boolean verify(byte[] secret, UUID messageId, Instant timestamp, byte[] exactPayload, String signature) {
        if (signature == null || !signature.startsWith(VERSION)) return false;
        try {
            byte[] supplied = Base64.getDecoder().decode(signature.substring(VERSION.length()));
            byte[] expected = Base64.getDecoder().decode(sign(secret, messageId, timestamp, exactPayload)
                    .substring(VERSION.length()));
            return MessageDigest.isEqual(expected, supplied);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static byte[] signingPrefix(UUID messageId, Instant timestamp) {
        byte[] id = messageId.toString().getBytes(StandardCharsets.US_ASCII);
        byte[] seconds = Long.toString(timestamp.getEpochSecond()).getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(id.length + seconds.length + 2)
                .put(id).put((byte) '.').put(seconds).put((byte) '.').array();
    }
}
