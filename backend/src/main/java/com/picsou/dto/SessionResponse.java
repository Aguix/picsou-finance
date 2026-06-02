package com.picsou.dto;

import java.time.Instant;

/**
 * One row in the user's "Active sessions" list. {@code current} is true for
 * the persistent session whose series_id matches the request's persistent_token
 * cookie — lets the UI label it and disable revoke for the row the user is
 * sitting on. {@code userAgent} and {@code ipPrefix} are intentionally fuzzy:
 * the goal is "did I create this from my phone last Tuesday?" recognition, not
 * forensic tracking.
 */
public record SessionResponse(
    Long id,
    String userAgent,
    String ipPrefix,
    Instant createdAt,
    Instant lastUsedAt,
    Instant expiresAt,
    boolean trustedFor2fa,
    boolean current
) {}
