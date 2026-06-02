package com.picsou.controller;

import com.picsou.dto.CashflowPeriod;
import com.picsou.dto.CashflowResponse;
import com.picsou.service.UserContext;
import com.picsou.service.budget.CashflowService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Cashflow view: income / expense / net for the current cycle or year-to-date. */
@RestController
@RequestMapping("/api/cashflow")
public class CashflowController {

    private final CashflowService cashflowService;
    private final UserContext userContext;

    public CashflowController(CashflowService cashflowService, UserContext userContext) {
        this.cashflowService = cashflowService;
        this.userContext = userContext;
    }

    @GetMapping
    public CashflowResponse cashflow(@RequestParam(defaultValue = "CYCLE") CashflowPeriod period) {
        return cashflowService.compute(userContext.currentMemberId(), period, LocalDate.now());
    }
}
