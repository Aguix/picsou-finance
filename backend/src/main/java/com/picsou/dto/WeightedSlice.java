package com.picsou.dto;

import java.math.BigDecimal;

/**
 * A single labelled slice of an ETF breakdown (a company, a country or a sector)
 * with its weight expressed as a percentage of the fund (0–100).
 */
public record WeightedSlice(
    String label,
    BigDecimal percent
) {}
