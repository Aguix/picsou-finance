package com.picsou.dto;

import java.math.BigDecimal;

public record ContributionBreakdownResponse(
    String memberName,
    BigDecimal amount
) {}
