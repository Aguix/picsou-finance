package com.picsou.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TransactionDto(
    Long id,
    LocalDate date,
    String description,
    BigDecimal amount,
    String type,
    String category,
    String nativeCurrency
) {}
