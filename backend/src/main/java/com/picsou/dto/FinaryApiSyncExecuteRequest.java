package com.picsou.dto;

import java.util.List;

public record FinaryApiSyncExecuteRequest(
    String syncToken,
    List<FinaryAccountMapping> mappings
) {}
