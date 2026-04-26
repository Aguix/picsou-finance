package com.picsou.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class CryptoKeyGeneratorServiceTest {

    @Test
    void ensureKey_createsBase64Aes256KeyOnFirstCall(@TempDir Path tmp) throws Exception {
        Path keyPath = tmp.resolve(".secrets").resolve("crypto_key");
        CryptoKeyGeneratorService service = new CryptoKeyGeneratorService(keyPath.toString());

        boolean created = service.ensureKey();

        assertThat(created).isTrue();
        assertThat(Files.exists(keyPath)).isTrue();

        // Must be a valid Base64-encoded 32-byte AES key (what CryptoEncryption expects).
        String content = Files.readString(keyPath).trim();
        byte[] decoded = Base64.getDecoder().decode(content);
        assertThat(decoded).hasSize(32);
    }

    @Test
    void ensureKey_isIdempotent_neverOverwritesExistingKey(@TempDir Path tmp) throws Exception {
        Path keyPath = tmp.resolve("crypto_key");
        CryptoKeyGeneratorService service = new CryptoKeyGeneratorService(keyPath.toString());

        service.ensureKey();
        String original = Files.readString(keyPath);

        boolean secondCall = service.ensureKey();

        assertThat(secondCall).isFalse();
        assertThat(Files.readString(keyPath)).isEqualTo(original);
    }

    @Test
    void exists_reportsAbsenceThenPresence(@TempDir Path tmp) {
        Path keyPath = tmp.resolve("crypto_key");
        CryptoKeyGeneratorService service = new CryptoKeyGeneratorService(keyPath.toString());

        assertThat(service.exists()).isFalse();
        service.ensureKey();
        assertThat(service.exists()).isTrue();
    }
}
