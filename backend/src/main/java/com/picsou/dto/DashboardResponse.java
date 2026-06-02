package com.picsou.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record DashboardResponse(
    BigDecimal totalNetWorth,
    BigDecimal totalLiabilities,
    List<NetWorthPoint> netWorthHistory,
    List<DistributionItem> distribution,
    List<DistributionItem> liabilities,
    List<GoalProgressResponse> goalSummaries
) {
    public record AccountPoint(BigDecimal total, BigDecimal invested, BigDecimal pnl) {}

    public record NetWorthPoint(
        LocalDate date,
        BigDecimal total,
        BigDecimal invested,
        BigDecimal pnl,
        Map<Long, AccountPoint> accounts
    ) {
        public NetWorthPoint(LocalDate date, BigDecimal total, BigDecimal invested, BigDecimal pnl) {
            this(date, total, invested, pnl, null);
        }
    }

    public record DistributionItem(
        Long accountId,
        String name,
        String color,
        BigDecimal balanceEur,
        double percentage,
        String accountType,
        boolean hasHoldings
    ) {}

    public record NetWorthIntradayPoint(
        LocalDateTime timestamp,
        BigDecimal total,
        BigDecimal invested
    ) {}
}
