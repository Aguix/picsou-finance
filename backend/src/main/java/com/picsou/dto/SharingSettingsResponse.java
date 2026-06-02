package com.picsou.dto;

import com.picsou.model.SharingLevel;

import java.util.List;

public record SharingSettingsResponse(
    String resourceType,
    SharingLevel sharingLevel,
    List<Long> sharedResourceIds
) {}
