package com.picsou.controller;

import com.picsou.crypto.CoinMappingRequest;
import com.picsou.crypto.CoinMappingResponse;
import com.picsou.crypto.CryptoImportRequest;
import com.picsou.crypto.CryptoImportResult;
import com.picsou.crypto.CryptoImportService;
import com.picsou.crypto.CryptoPreviewResponse;
import com.picsou.crypto.CryptoSourceInfo;
import com.picsou.crypto.CryptoStatsResponse;
import com.picsou.crypto.CryptoStatsService;
import com.picsou.model.CoinMapping;
import com.picsou.service.CoinMappingService;
import com.picsou.service.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Multi-exchange crypto CSV import + per-crypto statistics. The uploaded file's format is
 * auto-detected (Crypto.com App/Exchange, Kraken, Binance, Bybit, Bitstack, Ledger Live,
 * generic). All endpoints are member-scoped via {@link UserContext}; an access-key principal
 * acts only on its owner's data.
 */
@RestController
@RequestMapping("/api/crypto")
@RequiredArgsConstructor
public class CryptoController {

    private final CryptoImportService importService;
    private final CryptoStatsService statsService;
    private final CoinMappingService coinMappingService;
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

    /**
     * All known ticker → CoinGecko mappings, for the management UI. The {@code coin_mapping} cache
     * is global (keyed by ticker), not member-scoped, so the mapping endpoints need no member
     * filter — they just require an authenticated caller.
     */
    @GetMapping("/coin-mappings")
    public List<CoinMappingResponse> coinMappings() {
        return coinMappingService.listAll().stream().map(CoinMappingResponse::from).toList();
    }

    /**
     * Pin a ticker to the coin behind an operator-supplied CoinGecko link — used both to resolve a
     * ticker the preview couldn't auto-resolve and to correct a wrong mapping (which also purges
     * and refetches the ticker's price history).
     */
    @PostMapping("/coin-mappings")
    public CoinMappingResponse resolveCoin(@Valid @RequestBody CoinMappingRequest request) {
        CoinMapping m = coinMappingService.setManualMapping(request.ticker(), request.coingeckoUrl());
        return CoinMappingResponse.from(m);
    }

    /**
     * Mark a ticker as worthless — a delisted coin CoinGecko can't price. It's pinned to a value of
     * zero (history purged, holdings zeroed) instead of left silently unpriced. Reversible by
     * re-pinning a link ({@code POST}) or forgetting it ({@code DELETE}).
     */
    @PostMapping("/coin-mappings/{ticker}/worthless")
    public CoinMappingResponse markWorthless(@PathVariable String ticker) {
        return CoinMappingResponse.from(coinMappingService.markWorthless(ticker));
    }

    /**
     * Forget a mapping made by mistake: the ticker's price history is purged and it goes back to
     * unresolved (the next import preview re-runs auto-resolution).
     */
    @DeleteMapping("/coin-mappings/{ticker}")
    public void deleteCoinMapping(@PathVariable String ticker) {
        coinMappingService.delete(ticker);
    }

    /** Per-account stats — the per-exchange view (rewards detailed by program). */
    @GetMapping("/accounts/{id}/stats")
    public CryptoStatsResponse stats(@PathVariable Long id) {
        return statsService.stats(id, userContext.currentMemberId());
    }

    /** Consolidated stats pooling every coin across all of the member's CRYPTO accounts. */
    @GetMapping("/stats")
    public CryptoStatsResponse consolidatedStats() {
        return statsService.consolidatedStats(userContext.currentMemberId());
    }
}
