package com.picsou.controller;

import com.picsou.dto.DashboardResponse;
import com.picsou.dto.PnlResponse;
import com.picsou.service.HistoryService;
import com.picsou.service.UserContext;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/history")
public class HistoryController {

    private final HistoryService historyService;
    private final UserContext userContext;

    public HistoryController(HistoryService historyService, UserContext userContext) {
        this.historyService = historyService;
        this.userContext = userContext;
    }

    @GetMapping
    public List<DashboardResponse.NetWorthPoint> getHistory(
        @RequestParam List<Long> accountIds,
        @RequestParam(defaultValue = "12") int months,
        @RequestParam(defaultValue = "false") boolean split
    ) {
        return historyService.buildHistory(accountIds, months, split, userContext.currentMemberId());
    }

    @GetMapping("/pnl")
    public PnlResponse getPnl(
        @RequestParam List<Long> accountIds,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from
    ) {
        return historyService.buildPnl(accountIds, userContext.currentMemberId(), from);
    }

    @GetMapping("/net-worth/intraday")
    public List<DashboardResponse.NetWorthIntradayPoint> getIntraday(@RequestParam List<Long> accountIds) {
        return historyService.buildIntradayHistory(accountIds, userContext.currentMemberId());
    }
}
