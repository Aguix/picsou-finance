package com.picsou.config;

import com.picsou.repository.AppSettingRepository;
import com.picsou.service.SetupService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for whether auth cookies get the {@code Secure}
 * attribute. Mirrors {@link DynamicCorsConfigurationSource}: reads
 * {@code app.secure-cookies} from {@code app_setting} first, falls back to
 * the {@code SECURE_COOKIES} env var. Lets the setup wizard's Security
 * step toggle the flag without a container restart.
 */
@Component
public class SecureCookieProvider {

    private final AppSettingRepository settingRepository;
    private final boolean fallback;

    public SecureCookieProvider(AppSettingRepository settingRepository,
                                @Value("${app.secure-cookies:true}") boolean fallback) {
        this.settingRepository = settingRepository;
        this.fallback = fallback;
    }

    public boolean isSecure() {
        return settingRepository.findByKey(SetupService.KEY_SECURE_COOKIES)
            .map(s -> Boolean.parseBoolean(s.getValue()))
            .orElse(fallback);
    }
}
