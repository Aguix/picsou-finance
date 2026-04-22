package com.picsou.dto;

public record FinaryAutoSyncResponse(
    String status,        // "OK" | "NEEDS_MAPPING" | "TOTP_REQUIRED" | "NOT_CONNECTED"
    int accountsSynced,
    int newAccountCount
) {}
