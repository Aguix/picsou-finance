package com.picsou.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class MfaDtos {

    private MfaDtos() {}

    public record MfaStatusResponse(
        boolean enabled,
        Instant enrolledAt,
        int remainingRecoveryCodes
    ) {}

    public record EnrollInitRequest(
        @NotBlank @Size(max = 128) String currentPassword
    ) {}

    public record EnrollInitResponse(
        String qrCodeDataUri,
        String secret
    ) {}

    public record EnrollVerifyRequest(
        @NotBlank
        @Pattern(regexp = "\\d{6}", message = "TOTP code must be 6 digits")
        String code
    ) {}

    public record RecoveryCodesResponse(
        List<String> recoveryCodes
    ) {}

    public record MfaVerifyRequest(
        @NotBlank @Size(max = 16) String code,
        Boolean trustDevice,
        Boolean isRecoveryCode
    ) {}

    public record DisableMfaRequest(
        @NotBlank @Size(max = 128) String currentPassword,
        @NotBlank @Size(max = 16) String code,
        Boolean isRecoveryCode
    ) {}

    public record RegenerateCodesRequest(
        @NotBlank @Size(max = 128) String currentPassword,
        @NotBlank
        @Pattern(regexp = "\\d{6}", message = "TOTP code must be 6 digits")
        String code
    ) {}
}
