package com.picsou.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * The operator's decision for one symbol from the standing mapping UI (holding detail, registry
 * table). Applied by {@code AssetController} onto the {@code financial_asset} registry through the
 * same {@code FinancialAssetService} entry points the import preview uses, just reached outside an
 * import:
 * <ul>
 *   <li>{@code MAP} with {@code url} → resolve a pasted aggregator link and pin the asset behind it
 *       ({@code setManualMapping}); whichever aggregator recognises the link owns the id;</li>
 *   <li>{@code MAP} with {@code aggregatorIds} (one picked id per aggregator) → pin them directly
 *       ({@code applyMappings}), no extra round-trip;</li>
 *   <li>{@code WORTHLESS} → mark the symbol worthless, valued at zero ({@code markWorthless}).</li>
 * </ul>
 * All three land the row as {@code USER}/{@code WORTHLESS}. Forgetting a mapping is a separate
 * {@code DELETE}.
 *
 * <p>{@code aggregatorIds} maps {@code aggregatorKey → the id picked for that aggregator}: mapping one
 * symbol on several aggregators is what lets a second one quote it when the first is rate-limited or
 * off. An unknown key is ignored server-side, so this shape survives adding an aggregator. A
 * {@code url} takes precedence — an explicit paste is the stronger signal.
 *
 * @param url           an aggregator asset-page URL; resolved server-side, used for MAP over the ids.
 * @param aggregatorIds picked id per {@code aggregatorKey} (from the candidate blocks), for MAP.
 * @param name          the asset's display name for the picked mapping.
 */
public record AssetMappingRequest(
    @NotBlank String action,
    String url,
    Map<String, String> aggregatorIds,
    String name
) {}
