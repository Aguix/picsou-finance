package com.picsou.controller;

import com.picsou.exception.ResourceNotFoundException;
import com.picsou.service.AccountService;
import com.picsou.service.LoanAmortizationService;
import com.picsou.service.LoanAmortizationService.LoanScheduleResponse;
import com.picsou.service.LoanAmortizationService.LoanSummary;
import com.picsou.service.ManualTransactionService;
import com.picsou.service.UserContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountControllerLoanTest {

    @Mock AccountService accountService;
    @Mock UserContext userContext;
    @Mock ManualTransactionService manualTransactionService;

    @InjectMocks AccountController controller;

    @Test
    void getLoanSummary_delegatesToService_andReturnsResponse() {
        LoanSummary summary = new LoanSummary(
            60, 28, 32, LocalDate.parse("2029-01-01"),
            new BigDecimal("394.40"), new BigDecimal("380.00"),
            new BigDecimal("14.40"), BigDecimal.ZERO,
            new BigDecimal("23664.00"), new BigDecimal("23649.00"),
            new BigDecimal("15.00"), BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("11036.00"), new BigDecimal("11036.00"),
            BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("12613.00"), new BigDecimal("46.66")
        );
        LoanScheduleResponse expected = new LoanScheduleResponse(summary, List.of());
        when(userContext.currentMemberId()).thenReturn(1L);
        when(accountService.getLoanSummary(42L, 1L)).thenReturn(expected);

        LoanScheduleResponse actual = controller.getLoanSummary(42L);

        assertThat(actual).isSameAs(expected);
        assertThat(actual.summary().paidInstallments()).isEqualTo(28);
        assertThat(actual.summary().remainingBalance()).isEqualByComparingTo("12613.00");
    }

    @Test
    void getLoanSummary_propagates404WhenServiceThrows() {
        when(userContext.currentMemberId()).thenReturn(1L);
        when(accountService.getLoanSummary(99L, 1L))
            .thenThrow(new ResourceNotFoundException("Debt details not set for account: 99"));

        assertThatThrownBy(() -> controller.getLoanSummary(99L))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Debt details");
    }

    @Test
    void getLoanSummary_propagates400WhenAccountIsNotLoan() {
        when(userContext.currentMemberId()).thenReturn(1L);
        when(accountService.getLoanSummary(7L, 1L))
            .thenThrow(new IllegalArgumentException("Account is not a loan: 7"));

        assertThatThrownBy(() -> controller.getLoanSummary(7L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not a loan");
    }
}
