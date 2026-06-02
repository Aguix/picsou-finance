package com.picsou.dto;

import java.util.List;

public record FinaryImportRequest(
    List<FinaryAccountMapping> mappings,
    String fileToken
) {}
