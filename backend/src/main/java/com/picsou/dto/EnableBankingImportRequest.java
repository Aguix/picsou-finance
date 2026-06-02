package com.picsou.dto;

import jakarta.validation.constraints.NotBlank;

public record EnableBankingImportRequest(
    @NotBlank String privatePem
) {}
