package com.picsou.dto;

/**
 * Returned after the wizard's admin step. Does not include the JWT — the
 * wizard continues on the unauthenticated path until
 * {@code POST /api/setup/complete} flips the state, at which point the
 * frontend calls {@code /api/auth/login} with the plaintext it already has
 * in memory to obtain cookies.
 */
public record SetupAdminResponse(String username, String displayName) {}
