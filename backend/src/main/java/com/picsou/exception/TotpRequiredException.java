package com.picsou.exception;

/**
 * Thrown when Finary authentication requires a TOTP code that was not provided.
 * Returns HTTP 403 so the frontend knows to show the TOTP input field.
 */
public class TotpRequiredException extends RuntimeException {
    public TotpRequiredException(String message) {
        super(message);
    }
}
