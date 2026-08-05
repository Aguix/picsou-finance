package com.picsou.controller;

import com.picsou.crypto.CryptoImportRequest;
import com.picsou.crypto.CryptoImportResult;
import com.picsou.crypto.CryptoImportService;
import com.picsou.crypto.CryptoPreviewResponse;
import com.picsou.crypto.CryptoSourceInfo;
import com.picsou.crypto.CryptoStatsResponse;
import com.picsou.crypto.CryptoStatsService;
import com.picsou.service.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Multi-exchange crypto CSV import + per-crypto statistics. The uploaded file's format is
 * auto-detected against the registered {@link com.picsou.crypto.CryptoCsvParser}s. All endpoints
 * are member-scoped via {@link UserContext}; an access-key principal acts only on its owner's data.
 */
@RestController
@RequestMapping("/api/crypto")
@RequiredArgsConstructor
public class CryptoController {

    /** How long the /pricing long-poll waits before giving the client a "done" to refetch on. */
    private static final long PRICING_AWAIT_TIMEOUT_MS = 60_000;

    private final CryptoImportService importService;
    private final CryptoStatsService statsService;
    private final UserContext userContext;

    /** The supported CSV source formats, for the import UI. */
    @GetMapping("/sources")
    public List<CryptoSourceInfo> sources() {
        return importService.sources();
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CryptoPreviewResponse preview(@RequestParam("file") MultipartFile file) {
        return importService.preview(file, userContext.currentMemberId());
    }

    @PostMapping("/import")
    public CryptoImportResult importData(@Valid @RequestBody CryptoImportRequest request) {
        return importService.execute(request, userContext.currentMemberId());
    }

    /** Per-account stats — the per-exchange/wallet view (rewards detailed by program). */
    @GetMapping("/accounts/{id}/stats")
    public CryptoStatsResponse stats(@PathVariable Long id) {
        return statsService.stats(id, userContext.currentMemberId());
    }

    /** Consolidated stats pooling every coin across all of the member's CRYPTO accounts. */
    @GetMapping("/stats")
    public CryptoStatsResponse consolidatedStats() {
        return statsService.consolidatedStats(userContext.currentMemberId());
    }

    /**
     * Long-poll the background pricing job an import kicked off: resolves as soon as that account's
     * price backfill/valuation finishes (or immediately if none is pending), so the client fires one
     * request and refetches exactly when the freshly-imported holdings are priced. Server-side this is
     * non-blocking ({@link DeferredResult} — no Tomcat thread is held while waiting); it falls back to
     * a plain "done" on timeout, at which point the client simply refetches. 204 either way — the
     * value lives in the account/stats endpoints.
     */
    @GetMapping("/accounts/{id}/pricing")
    public DeferredResult<ResponseEntity<Void>> awaitPricing(@PathVariable Long id) {
        DeferredResult<ResponseEntity<Void>> result =
            new DeferredResult<>(PRICING_AWAIT_TIMEOUT_MS, ResponseEntity.noContent().build());
        CompletableFuture<Void> future = importService.pricingFuture(id, userContext.currentMemberId());
        if (future == null || future.isDone()) {
            result.setResult(ResponseEntity.noContent().build());
        } else {
            future.whenComplete((v, ex) -> result.setResult(ResponseEntity.noContent().build()));
        }
        return result;
    }
}
