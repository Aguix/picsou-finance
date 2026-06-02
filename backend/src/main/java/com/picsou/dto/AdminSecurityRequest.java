package com.picsou.dto;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
public record AdminSecurityRequest(
    @NotNull @Size(min = 1) List<String> allowedOrigins,
    boolean secureCookies
) {}
