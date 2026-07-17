package com.picsou.controller;

import com.picsou.dto.AssetCandidatesResponse;
import com.picsou.dto.AssetMappingRequest;
import com.picsou.dto.AssetResponse;
import com.picsou.service.FinancialAssetService;
import com.picsou.service.FinancialAssetService.AssetResolutionPreview;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Standing mapping/verification of the {@code financial_asset} registry — the always-available
 * counterpart to the crypto import preview. Surfaced from the holding detail (per-symbol) so a
 * mapping can be verified or corrected any time, not only while importing.
 *
 * <p>The registry is a global, member-agnostic catalogue (one row per symbol, shared across the
 * family), so these endpoints are authenticated but <em>not</em> member-scoped — unlike the
 * account/holding endpoints. They delegate straight to {@link FinancialAssetService}, the same
 * entry points {@link com.picsou.crypto.CryptoImportService} calls, so a mapping made here and one
 * confirmed during an import are identical (both land {@code USER}/{@code WORTHLESS}).
 */
@RestController
@RequestMapping("/api/assets")
@RequiredArgsConstructor
public class AssetController {

    private final FinancialAssetService assetService;

    /**
     * The whole {@code financial_asset} registry, for the standing management table (one row per
     * asset, one column per aggregator). Any authenticated member may read it; confirming/correcting
     * a row is the admin-only {@link #map}/{@link #forget}.
     */
    @GetMapping
    public List<AssetResponse> list() {
        return assetService.listAll().stream().map(AssetResponse::from).toList();
    }

    /**
     * Every aggregator's candidates for a symbol — one block per aggregator, each with its own
     * candidates, its market-cap dominant suggestion, and the ref it holds today — plus the symbol's
     * current registry status. This is the data behind the standing mapping editor (one picker per
     * aggregator, mirroring the import preview). Returned even for a coin already settled, so a mapping
     * can always be re-verified.
     */
    @GetMapping("/{symbol}/candidates")
    public AssetCandidatesResponse candidates(@PathVariable String symbol) {
        AssetResolutionPreview p = assetService.previewResolution(symbol);
        return AssetCandidatesResponse.from(p);
    }

    /**
     * Apply a mapping for {@code symbol}: pin a pasted aggregator link or the ids picked per aggregator
     * as {@code USER}, or mark the symbol worthless. Re-pinning an aggregator to a different id purges
     * and refetches the symbol's price history (handled by the service). A pasted {@code url} takes
     * precedence over the picked ids — an explicit paste is the stronger signal.
     */
    @PutMapping("/{symbol}/mapping")
    public AssetResponse map(@PathVariable String symbol, @Valid @RequestBody AssetMappingRequest request) {
        String action = request.action() == null ? "" : request.action().trim().toUpperCase();
        return switch (action) {
            case "MAP" -> AssetResponse.from(
                request.url() != null && !request.url().isBlank()
                    ? assetService.setManualMapping(symbol, request.url())
                    : assetService.applyMappings(symbol, request.aggregatorIds(), request.name()));
            case "WORTHLESS" -> AssetResponse.from(assetService.markWorthless(symbol));
            default -> throw new IllegalArgumentException(
                "Unknown mapping action: '" + request.action() + "' (expected MAP or WORTHLESS).");
        };
    }

    /**
     * Forget a symbol's <b>link</b> — clear the mapping and revert it to {@code PENDING}, keeping the
     * registry row so a holding's {@code asset_id} FK stays valid (a full row delete would fail for a
     * held symbol). Price history is purged; the next resolve (import preview or {@link #candidates})
     * re-runs auto-resolution. Returns the reverted asset.
     */
    @DeleteMapping("/{symbol}")
    public AssetResponse forget(@PathVariable String symbol) {
        return AssetResponse.from(assetService.clearMapping(symbol));
    }
}
