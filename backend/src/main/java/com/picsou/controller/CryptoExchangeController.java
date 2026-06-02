package com.picsou.controller;

import com.picsou.dto.AccountResponse;
import com.picsou.model.ExchangeType;
import com.picsou.service.CryptoExchangeSyncService;
import com.picsou.service.UserContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/crypto/exchange")
public class CryptoExchangeController {

    private final CryptoExchangeSyncService exchangeService;
    private final UserContext userContext;

    public CryptoExchangeController(CryptoExchangeSyncService exchangeService, UserContext userContext) {
        this.exchangeService = exchangeService;
        this.userContext = userContext;
    }

    @PostMapping
    public AccountResponse addExchange(@RequestBody AddExchangeRequest req) {
        return exchangeService.addExchange(req.type(), req.apiKey(), req.apiSecret(), userContext.currentMemberId());
    }

    @PostMapping("/{id}/sync")
    public AccountResponse sync(@PathVariable Long id) {
        return exchangeService.sync(id, userContext.currentMemberId());
    }

    @GetMapping("/status")
    public List<CryptoExchangeSyncService.ExchangeStatusResponse> getStatus() {
        return exchangeService.getStatus(userContext.currentMemberId());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> removeExchange(@PathVariable Long id) {
        exchangeService.removeExchange(id, userContext.currentMemberId());
        return ResponseEntity.noContent().build();
    }

    record AddExchangeRequest(ExchangeType type, String apiKey, String apiSecret) {}
}
