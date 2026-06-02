package com.picsou.controller;

import com.picsou.dto.AdminEnableBankingRequest;
import com.picsou.dto.AdminSecurityRequest;
import com.picsou.service.IntegrationsService;
import com.picsou.service.SetupService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static com.picsou.service.SetupService.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    @Mock SetupService setupService;
    @Mock IntegrationsService integrationsService;

    @InjectMocks AdminController controller;

    @Test
    void getSettings_returnsAssembledResponse() {
        when(setupService.readSetting(KEY_CORS_ALLOWED_ORIGINS)).thenReturn(Optional.of("https://a.com,https://b.com"));
        when(setupService.readSetting(KEY_SECURE_COOKIES)).thenReturn(Optional.of("true"));
        when(setupService.readSetting(KEY_ENABLEBANKING_APP_ID)).thenReturn(Optional.of("app-id"));
        when(setupService.readSetting(KEY_ENABLEBANKING_KEY_ID)).thenReturn(Optional.of("key-id"));
        when(setupService.readSetting(KEY_ENABLEBANKING_REDIRECT_URI)).thenReturn(Optional.of("https://app.com/callback"));
        for (String key : INTEGRATIONS) {
            when(integrationsService.isEnabled(key)).thenReturn("enablebanking".equals(key));
        }

        var response = controller.getSettings();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.security().allowedOrigins()).containsExactly("https://a.com", "https://b.com");
        assertThat(body.security().secureCookies()).isTrue();
        assertThat(body.enableBanking().applicationId()).isEqualTo("app-id");
        assertThat(body.integrations()).containsEntry("enablebanking", true);
        assertThat(body.integrations()).containsEntry("finary", false);
    }

    @Test
    void updateSecurity_delegatesToSetupService() {
        var request = new AdminSecurityRequest(List.of("https://app.com"), true);
        var response = controller.updateSecurity(request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(setupService).writeSecurity(List.of("https://app.com"), true);
    }

    @Test
    void updateEnableBanking_delegatesToSetupService() {
        var request = new AdminEnableBankingRequest("my-app", "my-key", "https://app.com/cb");
        var response = controller.updateEnableBanking(request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(setupService).writeEnableBankingConfig("my-app", "my-key", "https://app.com/cb");
    }

    @Test
    void toggleIntegration_enable_callsEnable() {
        var response = controller.toggleIntegration("finary", true);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(integrationsService).enable("finary");
        verify(integrationsService, never()).disable(any());
    }

    @Test
    void toggleIntegration_disable_callsDisable() {
        var response = controller.toggleIntegration("enablebanking", false);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(integrationsService).disable("enablebanking");
    }
}
