package com.picsou.controller;

import com.picsou.dto.SecurityInsightResponse;
import com.picsou.service.SecurityInsightService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Market-data insight for a security (asset type + ETF composition).
 * Not member-scoped — like {@link PriceController}, this is reference data.
 */
@RestController
@RequestMapping("/api/securities")
public class SecurityController {

    private final SecurityInsightService insightService;

    public SecurityController(SecurityInsightService insightService) {
        this.insightService = insightService;
    }

    /**
     * Asset type + composition for a ticker. The optional {@code name} helps
     * detect the ETF issuer (its name usually contains "iShares", "Amundi"...).
     */
    @GetMapping("/{ticker}/insight")
    public SecurityInsightResponse getInsight(
        @PathVariable String ticker,
        @RequestParam(required = false) String name
    ) {
        return insightService.getInsight(ticker, name);
    }
}
