package com.picsou.dto;

import java.math.BigDecimal;
import java.util.List;

public record FamilyDashboardResponse(
    List<SharedAccountInfo> sharedAccounts,
    List<SharedGoalInfo> sharedGoals,
    BigDecimal totalSharedNetWorth
) {
    public record SharedAccountInfo(
        Long id,
        String ownerName,
        String name,
        String type,
        String currency,
        BigDecimal balance,
        BigDecimal balanceEur
    ) {}

    public record SharedGoalInfo(
        Long id,
        String ownerName,
        String name,
        BigDecimal targetAmount,
        BigDecimal currentTotal,
        List<ContributionInfo> contributions
    ) {}

    public record ContributionInfo(
        String memberName,
        BigDecimal amount
    ) {}
}
