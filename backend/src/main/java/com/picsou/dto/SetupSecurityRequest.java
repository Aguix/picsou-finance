package com.picsou.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Wizard step 2 payload. Allowed origins are stored as a comma-separated
 * string in {@code app_setting.cors.allowed-origins} so they can be read
 * by the same {@code CorsConfigurationSource} used for env-driven installs.
 *
 * Each origin must match {@code scheme://host[:port]} — no trailing slashes,
 * no paths, no wildcards (we keep CORS permissive-enough via origin list
 * rather than letting users type {@code *} which would nullify {@code allowCredentials=true}).
 */
public record SetupSecurityRequest(
    @NotNull @NotEmpty
    List<@Pattern(regexp = "^https?://[A-Za-z0-9.\\-]+(:\\d{1,5})?$",
                  message = "Origin must look like http(s)://host[:port]") @Size(max = 200) String> allowedOrigins,

    boolean secureCookies
) {}
