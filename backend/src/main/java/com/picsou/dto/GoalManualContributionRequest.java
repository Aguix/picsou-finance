package com.picsou.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record GoalManualContributionRequest(
    @NotNull @DecimalMin("0") BigDecimal amount
) {}
