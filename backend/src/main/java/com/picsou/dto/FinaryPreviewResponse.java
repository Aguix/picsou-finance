package com.picsou.dto;

import java.util.List;

public record FinaryPreviewResponse(
    List<FinaryAccountPreview> accounts,
    List<AccountResponse> existingPicsouAccounts,
    int totalTransactionCount,
    String fileToken,
    boolean autoMapped,
    List<FinaryAccountMapping> suggestedMappings
) {}
