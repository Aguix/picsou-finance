package com.picsou.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DebtRequest(
    Long linkedAccountId,
    @NotNull @DecimalMin("0") BigDecimal borrowedAmount,
    @DecimalMin("0") @DecimalMax("1") BigDecimal interestRate,
    @DecimalMin("0") BigDecimal monthlyPayment,
    @Size(max = 100) String lenderName,
    LocalDate startDate,
    LocalDate endDate,
    @DecimalMin("0") BigDecimal insuranceMonthly,
    @DecimalMin("0") BigDecimal fileFees
) {}
