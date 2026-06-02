package com.picsou.dto;

public record ExportRequest(ReAuthDto reAuth, boolean includeBalanceSnapshots) {}
