package com.picsou.dto;

import com.picsou.model.SharingLevel;

import java.util.List;

public record SharingSettingsRequest(
    String resourceType,
    SharingLevel sharingLevel,
    List<Long> sharedResourceIds
) {}
