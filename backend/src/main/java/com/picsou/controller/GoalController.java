package com.picsou.controller;

import com.picsou.dto.DashboardResponse;
import com.picsou.dto.GoalManualContributionRequest;
import com.picsou.dto.GoalMonthEntryResponse;
import com.picsou.dto.GoalMonthOverrideRequest;
import com.picsou.dto.GoalProgressResponse;
import com.picsou.dto.GoalRequest;
import com.picsou.service.GoalService;
import com.picsou.service.UserContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/goals")
public class GoalController {

    private final GoalService goalService;
    private final UserContext userContext;

    public GoalController(GoalService goalService, UserContext userContext) {
        this.goalService = goalService;
        this.userContext = userContext;
    }

    @GetMapping
    public List<GoalProgressResponse> findAll() {
        return goalService.findAll(userContext.currentMemberId());
    }

    @GetMapping("/{id}")
    public GoalProgressResponse findById(@PathVariable Long id) {
        return goalService.findById(id, userContext.currentMemberId());
    }

    @GetMapping("/{id}/history")
    public List<DashboardResponse.NetWorthPoint> getHistory(@PathVariable Long id) {
        return goalService.getGoalHistory(id, userContext.currentMemberId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GoalProgressResponse create(@Valid @RequestBody GoalRequest req) {
        return goalService.create(req, userContext.currentMember());
    }

    @PutMapping("/{id}")
    public GoalProgressResponse update(@PathVariable Long id, @Valid @RequestBody GoalRequest req) {
        return goalService.update(id, req, userContext.currentMemberId());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        goalService.delete(id, userContext.currentMemberId());
    }

    @GetMapping("/{id}/months")
    public List<GoalMonthEntryResponse> getMonthlyEntries(@PathVariable Long id) {
        return goalService.getMonthlyEntries(id, userContext.currentMemberId());
    }

    @PostMapping("/{id}/history/extend")
    public GoalProgressResponse extendHistory(@PathVariable Long id) {
        return goalService.extendHistory(id, userContext.currentMemberId());
    }

    @PostMapping("/{id}/history/extend/month")
    public GoalProgressResponse extendHistoryByMonth(@PathVariable Long id) {
        return goalService.extendHistoryByMonth(id, userContext.currentMemberId());
    }

    @PutMapping("/{id}/months/{yearMonth}")
    public GoalMonthEntryResponse setMonthOverride(
        @PathVariable Long id,
        @PathVariable String yearMonth,
        @Valid @RequestBody GoalMonthOverrideRequest req
    ) {
        return goalService.setMonthOverride(id, yearMonth, req.amount(), userContext.currentMemberId());
    }

    @DeleteMapping("/{id}/months/{yearMonth}")
    public GoalMonthEntryResponse deleteMonthOverride(
        @PathVariable Long id,
        @PathVariable String yearMonth
    ) {
        return goalService.deleteMonthOverride(id, yearMonth, userContext.currentMemberId());
    }

    @PutMapping("/{id}/months/{yearMonth}/manual")
    public GoalMonthEntryResponse setManualContribution(
        @PathVariable Long id,
        @PathVariable String yearMonth,
        @Valid @RequestBody GoalManualContributionRequest req
    ) {
        return goalService.setManualContribution(id, yearMonth, req.amount(), userContext.currentMemberId());
    }

    @DeleteMapping("/{id}/months/{yearMonth}/manual")
    public GoalMonthEntryResponse deleteManualContribution(
        @PathVariable Long id,
        @PathVariable String yearMonth
    ) {
        return goalService.deleteManualContribution(id, yearMonth, userContext.currentMemberId());
    }
}
