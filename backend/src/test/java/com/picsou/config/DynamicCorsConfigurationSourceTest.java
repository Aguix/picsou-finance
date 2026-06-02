package com.picsou.config;

import com.picsou.model.AppSetting;
import com.picsou.repository.AppSettingRepository;
import com.picsou.service.SetupService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.cors.CorsConfiguration;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DynamicCorsConfigurationSourceTest {

    @Mock AppSettingRepository settingRepository;

    @Test
    void fallsBackToEnvOrigins_whenNoSettingPresent() {
        when(settingRepository.findByKey(SetupService.KEY_CORS_ALLOWED_ORIGINS))
            .thenReturn(Optional.empty());

        DynamicCorsConfigurationSource source =
            new DynamicCorsConfigurationSource(settingRepository, "https://a.example,https://b.example");

        CorsConfiguration cfg = source.getCorsConfiguration(apiRequest());

        assertThat(cfg).isNotNull();
        assertThat(cfg.getAllowedOrigins())
            .containsExactly("https://a.example", "https://b.example");
        assertThat(cfg.getAllowCredentials()).isTrue();
    }

    @Test
    void usesDbValue_whenPresent() {
        AppSetting stored = AppSetting.builder()
            .key(SetupService.KEY_CORS_ALLOWED_ORIGINS)
            .value("https://picsou.example.com,http://localhost:5173")
            .build();
        when(settingRepository.findByKey(SetupService.KEY_CORS_ALLOWED_ORIGINS))
            .thenReturn(Optional.of(stored));

        DynamicCorsConfigurationSource source =
            new DynamicCorsConfigurationSource(settingRepository, "*");

        CorsConfiguration cfg = source.getCorsConfiguration(apiRequest());

        assertThat(cfg.getAllowedOrigins())
            .containsExactly("https://picsou.example.com", "http://localhost:5173");
    }

    @Test
    void returnsNull_forNonApiRoutes() {
        DynamicCorsConfigurationSource source =
            new DynamicCorsConfigurationSource(settingRepository, "*");

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/assets/index.js");

        assertThat(source.getCorsConfiguration(req)).isNull();
    }

    @Test
    void fallsBackToEnv_whenDbValueIsEmptyCsv() {
        AppSetting stored = AppSetting.builder()
            .key(SetupService.KEY_CORS_ALLOWED_ORIGINS)
            .value("   , , ")
            .build();
        when(settingRepository.findByKey(SetupService.KEY_CORS_ALLOWED_ORIGINS))
            .thenReturn(Optional.of(stored));

        DynamicCorsConfigurationSource source =
            new DynamicCorsConfigurationSource(settingRepository, "https://fallback");

        CorsConfiguration cfg = source.getCorsConfiguration(apiRequest());

        assertThat(cfg.getAllowedOrigins()).containsExactly("https://fallback");
    }

    private static HttpServletRequest apiRequest() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/api/dashboard");
        return req;
    }
}
