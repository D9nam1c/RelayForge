package io.relayforge.security;

import io.relayforge.contracts.EncryptedSecret;

public interface SecretCipher {
    EncryptedSecret encrypt(byte[] plaintext, String context);
    byte[] decrypt(EncryptedSecret encrypted, String context);
}
