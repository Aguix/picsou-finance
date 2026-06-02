package com.picsou.finary.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FinaryOrgMember(
    String id,
    String memberType,
    FinaryOrgUser user
) {}
