package com.picsou.finary.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FinaryOrgUser(
    String fullname,
    boolean isOrganizationOwner
) {}
