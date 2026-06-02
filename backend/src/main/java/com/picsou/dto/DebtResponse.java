package com.picsou.dto;

import com.picsou.model.Debt;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DebtResponse(
    Long linkedAccountId,
    String linkedAccountName,
    BigDecimal borrowedAmount,
    BigDecimal interestRate,
    BigDecimal monthlyPayment,
    String lenderName,
    LocalDate startDate,
    LocalDate endDate,
    BigDecimal insuranceMonthly,
    BigDecimal fileFees
) {
    public static DebtResponse from(Debt d) {
        return new DebtResponse(
            d.getLinkedAccount() != null ? d.getLinkedAccount().getId() : null,
            d.getLinkedAccount() != null ? d.getLinkedAccount().getName() : null,
            d.getBorrowedAmount(),
            d.getInterestRate(),
            d.getMonthlyPayment(),
            d.getLenderName(),
            d.getStartDate(),
            d.getEndDate(),
            d.getInsuranceMonthly(),
            d.getFileFees()
        );
    }
}
