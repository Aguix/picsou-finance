package com.picsou.crypto;

import java.util.Map;

/**
 * The operator's decision for one previewed coin (see {@link ImportAssetChoice}), sent with the
 * import request and applied by {@code execute()} <b>before</b> the price backfill:
 * <ul>
 *   <li>{@code MAP} — pin {@code aggregatorIds} (with the chosen candidate's {@code name}) as a
 *       {@code USER} mapping;</li>
 *   <li>{@code WORTHLESS} — mark the symbol worthless (valued at zero);</li>
 *   <li>{@code IGNORE} — leave it unresolved; it imports unpriced and is re-presented next time.</li>
 * </ul>
 *
 * <p>{@code aggregatorIds} maps {@code aggregatorKey → the id picked for that aggregator}, one entry
 * per aggregator the operator chose a match for — mapping one coin on two aggregators is what lets
 * the second price it when the first is rate-limited or off. Aggregators left unpicked are simply
 * absent, and an unknown key is ignored server-side, so this shape survives adding an aggregator.
 *
 * <p>{@code url} is the wizard's "paste a link" escape hatch for a coin the searches couldn't offer
 * (unknown symbol, or a rate-limited preview): the link is resolved server-side by whichever
 * aggregator recognises it and its id merged into the mapping — outranking a picked candidate for
 * that same aggregator, an explicit paste being the stronger signal.
 *
 * <p>Unknown or malformed decisions are treated as {@code IGNORE} — a bad mapping never blocks the
 * import.
 */
public record ImportAssetMapping(
    String symbol,
    String action,
    Map<String, String> aggregatorIds,
    String url,
    String name
) {}
