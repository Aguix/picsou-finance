package com.picsou.controller;

import com.picsou.dto.AdminAggregatorResponse;
import com.picsou.dto.AggregatorSessionRequest;
import com.picsou.model.Aggregator;
import com.picsou.model.AggregatorSession;
import com.picsou.service.AggregatorService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin management of the price {@link Aggregator}s and their API credentials — the runtime home for
 * keys that used to live in the {@code COINGECKO_DEMO_API_KEY} env var. App-global config, gated to
 * {@code ROLE_ADMIN} by {@code SecurityConfig} (the whole {@code /api/admin/**} tree).
 *
 * <p>Secrets are write-only here: a session is created with a raw key/secret (encrypted by
 * {@link AggregatorService} before it touches the DB) and read back only as boolean "is set" flags —
 * the plaintext never leaves the adapter path.
 */
@RestController
@RequestMapping("/api/admin/aggregators")
public class AdminAggregatorController {

    private final AggregatorService aggregatorService;

    public AdminAggregatorController(AggregatorService aggregatorService) {
        this.aggregatorService = aggregatorService;
    }

    /** Every aggregator with its sessions (secret values redacted to presence flags). */
    @GetMapping
    public List<AdminAggregatorResponse> list() {
        return aggregatorService.listAggregators().stream()
            .map(this::toResponse)
            .toList();
    }

    /** Pause or resume a whole aggregator (its sessions are skipped while it is disabled). */
    @PatchMapping("/{key}")
    public ResponseEntity<Void> toggleAggregator(@PathVariable String key, @RequestParam boolean enabled) {
        aggregatorService.setAggregatorEnabled(key, enabled);
        return ResponseEntity.noContent().build();
    }

    /** Add a credential session to an aggregator; the key/secret are encrypted before persisting. */
    @PostMapping("/{key}/sessions")
    public ResponseEntity<AdminAggregatorResponse.Session> addSession(
            @PathVariable String key, @Valid @RequestBody AggregatorSessionRequest request) {
        AggregatorSession saved = aggregatorService.createSession(
            key, request.label(), request.apiKey(), request.apiSecret());
        return ResponseEntity.status(HttpStatus.CREATED).body(toSession(saved));
    }

    /** Pause or resume a single credential session without deleting it. */
    @PatchMapping("/sessions/{id}")
    public ResponseEntity<Void> toggleSession(@PathVariable Long id, @RequestParam boolean enabled) {
        aggregatorService.setSessionEnabled(id, enabled);
        return ResponseEntity.noContent().build();
    }

    /** Forget a credential session entirely. */
    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Void> deleteSession(@PathVariable Long id) {
        aggregatorService.deleteSession(id);
        return ResponseEntity.noContent().build();
    }

    private AdminAggregatorResponse toResponse(Aggregator aggregator) {
        List<AdminAggregatorResponse.Session> sessions =
            aggregatorService.sessionsFor(aggregator.getAggregatorKey()).stream()
                .map(this::toSession)
                .toList();
        return new AdminAggregatorResponse(
            aggregator.getAggregatorKey(), aggregator.getDisplayName(), aggregator.isEnabled(), sessions);
    }

    private AdminAggregatorResponse.Session toSession(AggregatorSession session) {
        return new AdminAggregatorResponse.Session(
            session.getId(),
            session.getLabel(),
            session.isEnabled(),
            session.getApiKey() != null,     // presence only — never the ciphertext, never the plaintext
            session.getApiSecret() != null,
            session.getLastSyncAt(),
            session.getCreatedAt());
    }
}
