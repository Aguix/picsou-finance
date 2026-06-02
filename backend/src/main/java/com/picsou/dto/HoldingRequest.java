package com.picsou.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record HoldingRequest(
    @NotNull BigDecimal quantity,
    BigDecimal averageBuyIn
) {}
