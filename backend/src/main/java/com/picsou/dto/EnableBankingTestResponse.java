package com.picsou.dto;

/**
 * Structured result for the "Test connection" substep. {@code code} is a
 * machine-readable token the frontend uses to pick a localized hint:
 * {@code invalid_key_id}, {@code public_key_not_uploaded},
 * {@code redirect_uri_mismatch}, {@code invalid_application_id},
 * {@code network}, or {@code unknown}. {@code hint} is the human-readable
 * fallback in case the frontend has no translation for a newer code.
 */
public record EnableBankingTestResponse(boolean ok, String code, String hint) {

    public static EnableBankingTestResponse success() {
        return new EnableBankingTestResponse(true, "ok", "Enable Banking is ready to use.");
    }

    public static EnableBankingTestResponse failure(String code, String hint) {
        return new EnableBankingTestResponse(false, code, hint);
    }
}
