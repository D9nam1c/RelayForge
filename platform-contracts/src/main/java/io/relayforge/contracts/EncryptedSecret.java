package io.relayforge.contracts;

import java.util.Objects;

public record EncryptedSecret(String keyId, String ciphertext) {
    public EncryptedSecret {
        if (keyId == null || keyId.isBlank()) throw new IllegalArgumentException("keyId is required");
        if (ciphertext == null || ciphertext.isBlank()) throw new IllegalArgumentException("ciphertext is required");
    }
}
