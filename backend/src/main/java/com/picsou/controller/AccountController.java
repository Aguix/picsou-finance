package com.picsou.controller;

import com.picsou.dto.AccountRequest;
import com.picsou.dto.AccountResponse;
import com.picsou.dto.DebtRequest;
import com.picsou.dto.DebtResponse;
import com.picsou.dto.HoldingResponse;
import com.picsou.dto.RealEstateMetadataRequest;
import com.picsou.dto.RealEstateMetadataResponse;
import com.picsou.dto.SnapshotRequest;
import com.picsou.dto.TransactionResponse;
import com.picsou.model.BalanceSnapshot;
import com.picsou.service.AccountService;
import com.picsou.service.UserContext;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;
    private final UserContext userContext;

    public AccountController(AccountService accountService, UserContext userContext) {
        this.accountService = accountService;
        this.userContext = userContext;
    }

    @GetMapping
    public List<AccountResponse> findAll() {
        return accountService.findAll(userContext.currentMemberId());
    }

    @GetMapping("/{id}")
    public AccountResponse findById(@PathVariable Long id) {
        return accountService.findById(id, userContext.currentMemberId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse create(@Valid @RequestBody AccountRequest req) {
        return accountService.create(req, userContext.currentMember());
    }

    @PutMapping("/{id}")
    public AccountResponse update(@PathVariable Long id, @Valid @RequestBody AccountRequest req) {
        return accountService.update(id, req, userContext.currentMemberId());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        accountService.delete(id, userContext.currentMemberId());
    }

    @GetMapping("/{id}/history")
    public List<BalanceSnapshot> getHistory(
        @PathVariable Long id,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return accountService.getHistory(id, userContext.currentMemberId(), from, to);
    }

    @PostMapping("/{id}/snapshot")
    @ResponseStatus(HttpStatus.CREATED)
    public BalanceSnapshot addSnapshot(
        @PathVariable Long id,
        @Valid @RequestBody SnapshotRequest req
    ) {
        return accountService.addManualSnapshot(id, userContext.currentMemberId(), req);
    }

    @GetMapping("/{id}/holdings")
    public List<HoldingResponse> getHoldings(@PathVariable Long id) {
        return accountService.getHoldings(id, userContext.currentMemberId());
    }

    @GetMapping("/{id}/transactions")
    public List<TransactionResponse> getTransactions(@PathVariable Long id) {
        return accountService.getTransactions(id, userContext.currentMemberId());
    }

    @PutMapping("/{id}/real-estate")
    public RealEstateMetadataResponse updateRealEstateMetadata(
        @PathVariable Long id,
        @Valid @RequestBody RealEstateMetadataRequest req
    ) {
        return accountService.updateRealEstateMetadata(id, userContext.currentMemberId(), req);
    }

    @PutMapping("/{id}/debt")
    public DebtResponse updateDebtMetadata(
        @PathVariable Long id,
        @Valid @RequestBody DebtRequest req
    ) {
        return accountService.updateDebtMetadata(id, userContext.currentMemberId(), req);
    }
}
