package com.picsou.crypto;

/**
 * The operator's decision for one previewed coin (see {@link ImportAssetChoice}), sent with the
 * import request and applied by {@code execute()} <b>before</b> the price backfill:
 * <ul>
 *   <li>{@code MAP} — pin {@code coingeckoId} (with the chosen candidate's {@code name}) as a
 *       {@code USER} mapping;</li>
 *   <li>{@code WORTHLESS} — mark the symbol worthless (valued at zero);</li>
 *   <li>{@code IGNORE} — leave it unresolved; it imports unpriced and is re-presented next time.</li>
 * </ul>
 * Unknown or malformed decisions are treated as {@code IGNORE} — a bad mapping never blocks the
 * import.
 */
public record ImportAssetMapping(
    String symbol,
    String action,
    String coingeckoId,
    String name
) {}
