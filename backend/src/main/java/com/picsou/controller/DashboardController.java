package com.picsou.controller;

import com.picsou.dto.DashboardResponse;
import com.picsou.service.DashboardService;
import com.picsou.service.UserContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;
    private final UserContext userContext;

    public DashboardController(DashboardService dashboardService, UserContext userContext) {
        this.dashboardService = dashboardService;
        this.userContext = userContext;
    }

    @GetMapping
    public DashboardResponse getDashboard(@RequestParam(required = false) String range) {
        return dashboardService.getDashboard(userContext.currentMemberId(), range);
    }
}
