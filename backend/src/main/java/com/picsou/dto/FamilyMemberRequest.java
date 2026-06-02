package com.picsou.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FamilyMemberRequest(
    @NotBlank @Size(max = 100) String displayName,
    String avatarColor
) {}
