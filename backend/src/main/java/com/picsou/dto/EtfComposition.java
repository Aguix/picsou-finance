package com.picsou.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Composition breakdowns of an ETF, each as a list of {@link WeightedSlice}
 * sorted by descending weight. Any breakdown may be empty when the issuer's
 * holdings file does not expose it.
 *
 * @param source the issuer the data came from (e.g. "Boursorama")
 * @param asOf   the holdings file date when known, else null
 */
public record EtfComposition(
    List<WeightedSlice> companies,
    List<WeightedSlice> countries,
    List<WeightedSlice> sectors,
    String source,
    LocalDate asOf
) {}
