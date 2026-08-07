package io.relayforge.security;

import io.relayforge.contracts.EncryptedSecret;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Local key provider. Production deployments must replace this with KMS/Vault envelope encryption. */
public final class LocalAesGcmSecretCipher implements SecretCipher {
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final String keyId;
    private final SecretKeySpec key;
    private final SecureRandom random;

    public LocalAesGcmSecretCipher(String keyId, String base64Key) {
        this(keyId, base64Key, new SecureRandom());
    }

    LocalAesGcmSecretCipher(String keyId, String base64Key, SecureRandom random) {
        this.keyId = keyId;
        byte[] decoded = Base64.getDecoder().decode(base64Key);
        if (decoded.length != 32) throw new IllegalArgumentException("AES-256 key must be exactly 32 bytes");
        this.key = new SecretKeySpec(decoded, "AES");
        this.random = random;
    }

    @Override
    public EncryptedSecret encrypt(byte[] plaintext, String context) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] combined = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(ciphertext, 0, combined, nonce.length, ciphertext.length);
            return new EncryptedSecret(keyId, Base64.getEncoder().encodeToString(combined));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not encrypt secret", exception);
        }
    }

    @Override
    public byte[] decrypt(EncryptedSecret encrypted, String context) {
        if (!keyId.equals(encrypted.keyId())) throw new IllegalArgumentException("Unknown key ID");
        byte[] combined = Base64.getDecoder().decode(encrypted.ciphertext());
        if (combined.length <= NONCE_BYTES) throw new IllegalArgumentException("Invalid ciphertext");
        byte[] nonce = new byte[NONCE_BYTES];
        byte[] ciphertext = new byte[combined.length - NONCE_BYTES];
        System.arraycopy(combined, 0, nonce, 0, NONCE_BYTES);
        System.arraycopy(combined, NONCE_BYTES, ciphertext, 0, ciphertext.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("Secret authentication failed", exception);
        }
    }
}
