package com.picsou.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EnableBankingKeyPairServiceTest {

    @Test
    void firstCall_generatesKeyPairAndWritesPrivatePem(@TempDir Path dir) throws Exception {
        Path privPath = dir.resolve("enablebanking-private.pem");
        EnableBankingKeyPairService svc = new EnableBankingKeyPairService(privPath.toString());

        assertThat(svc.exists()).isFalse();
        String publicPem = svc.getOrGeneratePublicPem();

        assertThat(publicPem).contains("-----BEGIN PUBLIC KEY-----");
        assertThat(publicPem).contains("-----END PUBLIC KEY-----");
        assertThat(Files.exists(privPath)).isTrue();
        assertThat(Files.readString(privPath)).contains("-----BEGIN PRIVATE KEY-----");
    }

    @Test
    void secondCall_isIdempotent_andReturnsTheSamePublicKey(@TempDir Path dir) {
        Path privPath = dir.resolve("enablebanking-private.pem");
        EnableBankingKeyPairService svc = new EnableBankingKeyPairService(privPath.toString());

        String first = svc.getOrGeneratePublicPem();
        String second = svc.getOrGeneratePublicPem();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void privateKeyFile_hasOwnerOnlyPermissions_onPosixSystems(@TempDir Path dir) throws Exception {
        Path privPath = dir.resolve("enablebanking-private.pem");
        EnableBankingKeyPairService svc = new EnableBankingKeyPairService(privPath.toString());
        svc.getOrGeneratePublicPem();

        if (!Files.getFileStore(privPath).supportsFileAttributeView("posix")) {
            return; // Skip on Windows/FAT — service makes best effort only.
        }
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(privPath);
        assertThat(perms).containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
        );
    }
}
