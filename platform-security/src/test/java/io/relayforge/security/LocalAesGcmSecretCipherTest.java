package io.relayforge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.relayforge.contracts.EncryptedSecret;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class LocalAesGcmSecretCipherTest {
    private final LocalAesGcmSecretCipher cipher = new LocalAesGcmSecretCipher("local-v1",
            Base64.getEncoder().encodeToString(new byte[32]));

    @Test
    void encryptsWithContextBindingAndRandomNonce() {
        byte[] plaintext = "secret".getBytes(StandardCharsets.UTF_8);
        EncryptedSecret first = cipher.encrypt(plaintext, "endpoint-a");
        EncryptedSecret second = cipher.encrypt(plaintext, "endpoint-a");

        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(cipher.decrypt(first, "endpoint-a")).isEqualTo(plaintext);
        assertThatThrownBy(() -> cipher.decrypt(first, "endpoint-b"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
