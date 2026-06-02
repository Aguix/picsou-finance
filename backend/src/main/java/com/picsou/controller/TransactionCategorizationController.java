package com.picsou.controller;

import com.picsou.dto.CategorizeRequest;
import com.picsou.dto.TransactionResponse;
import com.picsou.service.UserContext;
import com.picsou.service.budget.CategorizationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The "to categorize" inbox: lists member transactions with no managed category and lets
 * the user assign one (optionally learning a rule from it). Routes live under
 * {@code /api/transactions} since they operate on transactions across all accounts.
 */
@RestController
@RequestMapping("/api/transactions")
public class TransactionCategorizationController {

    private final CategorizationService categorizationService;
    private final UserContext userContext;

    public TransactionCategorizationController(CategorizationService categorizationService, UserContext userContext) {
        this.categorizationService = categorizationService;
        this.userContext = userContext;
    }

    @GetMapping("/uncategorized")
    public List<TransactionResponse> uncategorized() {
        return categorizationService.findUncategorized(userContext.currentMemberId());
    }

    @PutMapping("/{id}/category")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void categorize(@PathVariable Long id, @Valid @RequestBody CategorizeRequest req) {
        categorizationService.categorize(id, req.categoryId(), req.createRule(), userContext.currentMemberId());
    }
}
