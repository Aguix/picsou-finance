package com.picsou.config;

import com.picsou.model.AppSetting;
import com.picsou.repository.AppSettingRepository;
import com.picsou.service.SetupService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SecureCookieProviderTest {

    @Mock AppSettingRepository settingRepository;

    @Test
    void fallsBackToEnvValue_whenSettingAbsent() {
        when(settingRepository.findByKey(SetupService.KEY_SECURE_COOKIES))
            .thenReturn(Optional.empty());

        SecureCookieProvider secureDefault = new SecureCookieProvider(settingRepository, true);
        assertThat(secureDefault.isSecure()).isTrue();

        SecureCookieProvider insecureDefault = new SecureCookieProvider(settingRepository, false);
        assertThat(insecureDefault.isSecure()).isFalse();
    }

    @Test
    void readsDbValue_whenPresent() {
        AppSetting stored = AppSetting.builder()
            .key(SetupService.KEY_SECURE_COOKIES)
            .value("false")
            .build();
        when(settingRepository.findByKey(SetupService.KEY_SECURE_COOKIES))
            .thenReturn(Optional.of(stored));

        SecureCookieProvider provider = new SecureCookieProvider(settingRepository, true);

        assertThat(provider.isSecure()).isFalse();
    }
}
