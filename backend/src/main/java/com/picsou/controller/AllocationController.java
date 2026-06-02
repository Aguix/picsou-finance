package com.picsou.controller;

import com.picsou.dto.AllocationResponse;
import com.picsou.dto.CashflowPeriod;
import com.picsou.service.UserContext;
import com.picsou.service.budget.AllocationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Allocation view: current stock by asset class + contributions flowed in over the period. */
@RestController
@RequestMapping("/api/allocation")
public class AllocationController {

    private final AllocationService allocationService;
    private final UserContext userContext;

    public AllocationController(AllocationService allocationService, UserContext userContext) {
        this.allocationService = allocationService;
        this.userContext = userContext;
    }

    @GetMapping
    public AllocationResponse allocation(@RequestParam(defaultValue = "CYCLE") CashflowPeriod period) {
        return allocationService.compute(userContext.currentMemberId(), period, LocalDate.now());
    }
}
