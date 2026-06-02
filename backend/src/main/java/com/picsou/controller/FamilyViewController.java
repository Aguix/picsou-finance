package com.picsou.controller;

import com.picsou.dto.ContributionBreakdownResponse;
import com.picsou.dto.FamilyDashboardResponse;
import com.picsou.model.FamilyMember;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.service.FamilyViewService;
import com.picsou.service.UserContext;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/family")
public class FamilyViewController {

    private final FamilyViewService familyViewService;
    private final FamilyMemberRepository memberRepository;
    private final UserContext userContext;

    public FamilyViewController(
        FamilyViewService familyViewService,
        FamilyMemberRepository memberRepository,
        UserContext userContext
    ) {
        this.familyViewService = familyViewService;
        this.memberRepository = memberRepository;
        this.userContext = userContext;
    }

    @GetMapping("/dashboard")
    public FamilyDashboardResponse getDashboard() {
        return familyViewService.getFamilyDashboard(userContext.currentMemberId());
    }

    @GetMapping("/goals/{goalId}/contributions")
    public List<ContributionBreakdownResponse> getContributions(@PathVariable Long goalId) {
        List<FamilyMember> allMembers = memberRepository.findAllByOrderByCreatedAtAsc();
        return familyViewService.getGoalContributions(goalId, userContext.currentMemberId(), allMembers);
    }
}
