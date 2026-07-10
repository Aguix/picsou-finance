package com.picsou.dto;

import jakarta.validation.constraints.Size;

/**
 * Add a credential session to a price aggregator. All fields are optional and hold the <em>raw</em>
 * key/secret — the service encrypts them before persisting. {@code apiKey}/{@code apiSecret} are
 * capped so their AES-GCM ciphertext stays within the 500-char {@code aggregator_session} columns.
 */
public record AggregatorSessionRequest(
    @Size(max = 100) String label,
    @Size(max = 200) String apiKey,
    @Size(max = 200) String apiSecret
) {}
