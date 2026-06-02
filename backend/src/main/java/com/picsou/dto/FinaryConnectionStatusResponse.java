package com.picsou.dto;

import java.time.Instant;

public record FinaryConnectionStatusResponse(
    boolean connected,
    Long sessionId,
    String status,
    Instant lastSyncedAt,
    String maskedEmail
) {}
