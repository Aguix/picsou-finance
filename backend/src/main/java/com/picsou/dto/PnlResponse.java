package com.picsou.dto;

import java.math.BigDecimal;

public record PnlResponse(
    BigDecimal total,
    BigDecimal invested,
    BigDecimal pnl,
    BigDecimal pnlPercent,
    BigDecimal valueAtFrom,
    BigDecimal rangePnl,
    BigDecimal rangePnlPercent
) {
    public PnlResponse(BigDecimal total, BigDecimal invested, BigDecimal pnl, BigDecimal pnlPercent) {
        this(total, invested, pnl, pnlPercent, null, null, null);
    }
}
