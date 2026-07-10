package com.picsou.dto;

import java.time.Instant;
import java.util.List;

/**
 * One price aggregator and its credential sessions, for the admin panel. Secrets are never exposed:
 * a session only reports <em>whether</em> a key/secret is set ({@link Session#hasKey}/{@link Session#hasSecret}),
 * never the value.
 */
public record AdminAggregatorResponse(
    String key,
    String displayName,
    boolean enabled,
    List<Session> sessions
) {
    public record Session(
        Long id,
        String label,
        boolean enabled,
        boolean hasKey,
        boolean hasSecret,
        Instant lastSyncAt,
        Instant createdAt
    ) {}
}
