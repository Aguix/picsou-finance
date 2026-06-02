package com.picsou.service;

import com.picsou.dto.ReAuthDto;
import com.picsou.model.AppUser;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class ReAuthService {

    public static class ReAuthFailedException extends RuntimeException {
        public ReAuthFailedException(String message) {
            super(message);
        }
    }

    private final MfaService mfaService;
    private final PasswordEncoder passwordEncoder;

    public ReAuthService(MfaService mfaService, PasswordEncoder passwordEncoder) {
        this.mfaService = mfaService;
        this.passwordEncoder = passwordEncoder;
    }

    public void verify(AppUser user, ReAuthDto reAuth) {
        if (reAuth == null) {
            throw new ReAuthFailedException("re-auth payload missing");
        }
        if (mfaService.isEnabled(user)) {
            if (reAuth.totpCode() == null || reAuth.totpCode().isBlank()) {
                throw new ReAuthFailedException("totp required");
            }
            if (!mfaService.verifyTotp(user, reAuth.totpCode())) {
                throw new ReAuthFailedException("invalid totp");
            }
        } else {
            if (reAuth.password() == null || reAuth.password().isBlank()) {
                throw new ReAuthFailedException("password required");
            }
            if (!passwordEncoder.matches(reAuth.password(), user.getPasswordHash())) {
                throw new ReAuthFailedException("invalid password");
            }
        }
    }
}
