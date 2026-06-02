package com.picsou.config;

import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class TotpConfig {

    /**
     * System UTC clock — injectable wherever a service needs deterministic timestamps
     * (e.g. {@code PersistentSessionService}). Tests can override the bean with a
     * fixed clock.
     */
    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    public SecretGenerator secretGenerator() {
        // 32-character base32 = 160 bits of entropy (RFC 6238 recommendation)
        return new DefaultSecretGenerator(32);
    }

    @Bean
    public CodeGenerator codeGenerator() {
        return new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
    }

    @Bean
    public TimeProvider totpTimeProvider() {
        return new SystemTimeProvider();
    }

    @Bean
    public QrGenerator qrGenerator() {
        return new ZxingPngQrGenerator();
    }
}
