package com.picsou.dto;

/**
 * Outcome of the crypto encryption-key generation substep. {@code existed}
 * is true when the key file was already on disk — in that case the wizard
 * shows a "You're all set" state rather than a "Key generated" one.
 */
public record CryptoKeyGenerateResponse(boolean existed, String path) {}
