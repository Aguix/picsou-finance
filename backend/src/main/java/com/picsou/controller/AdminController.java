package com.picsou.controller;

import com.picsou.dto.AdminEnableBankingRequest;
import com.picsou.dto.AdminSecurityRequest;
import com.picsou.dto.AdminSettingsResponse;
import com.picsou.service.IntegrationsService;
import com.picsou.service.SetupService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.picsou.service.SetupService.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final SetupService setupService;
    private final IntegrationsService integrationsService;
    private final String envAllowedOrigins;

    public AdminController(SetupService setupService,
                           IntegrationsService integrationsService,
                           @Value("${app.cors.allowed-origins:}") String envAllowedOrigins) {
        this.setupService = setupService;
        this.integrationsService = integrationsService;
        this.envAllowedOrigins = envAllowedOrigins;
    }

    @GetMapping("/settings")
    public ResponseEntity<AdminSettingsResponse> getSettings() {
        List<String> origins = setupService.readSetting(KEY_CORS_ALLOWED_ORIGINS)
            .map(s -> Arrays.asList(s.split(",")))
            .orElse(List.of());
        boolean secureCookies = setupService.readSetting(KEY_SECURE_COOKIES)
            .map(Boolean::parseBoolean).orElse(false);
        String appId = setupService.readSetting(KEY_ENABLEBANKING_APP_ID).orElse("");
        String keyId = setupService.readSetting(KEY_ENABLEBANKING_KEY_ID).orElse("");
        String redirectUri = setupService.readSetting(KEY_ENABLEBANKING_REDIRECT_URI).orElse("");

        Map<String, Boolean> integrations = new LinkedHashMap<>();
        for (String name : INTEGRATIONS) {
            integrations.put(name, integrationsService.isEnabled(name));
        }

        return ResponseEntity.ok(new AdminSettingsResponse(
            new AdminSettingsResponse.SecuritySettings(origins, secureCookies),
            new AdminSettingsResponse.EnableBankingSettings(appId, keyId, redirectUri),
            integrations
        ));
    }

    @PutMapping("/settings/security")
    public ResponseEntity<Void> updateSecurity(@Valid @RequestBody AdminSecurityRequest request) {
        setupService.writeSecurity(request.allowedOrigins(), request.secureCookies());
        return ResponseEntity.noContent().build();
    }

    /**
     * Overwrites the persisted CORS origins with the live value of the
     * {@code ALLOWED_ORIGINS} env var. Useful when the operator has changed
     * the public URL (e.g. moved from http to https) but the wizard already
     * locked a stale value into {@code app_setting}.
     */
    @PostMapping("/settings/cors/reload-from-env")
    public ResponseEntity<Map<String, Object>> reloadCorsFromEnv() {
        setupService.reloadCorsFromEnv(envAllowedOrigins);
        List<String> origins = Arrays.stream(envAllowedOrigins.split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).toList();
        return ResponseEntity.ok(Map.of("allowedOrigins", origins));
    }

    @PutMapping("/settings/enablebanking")
    public ResponseEntity<Void> updateEnableBanking(@Valid @RequestBody AdminEnableBankingRequest request) {
        setupService.writeEnableBankingConfig(request.applicationId(), request.keyId(), request.redirectUri());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/settings/integrations/{key}")
    public ResponseEntity<Void> toggleIntegration(@PathVariable String key, @RequestParam boolean enabled) {
        if (enabled) integrationsService.enable(key);
        else integrationsService.disable(key);
        return ResponseEntity.noContent().build();
    }
}
