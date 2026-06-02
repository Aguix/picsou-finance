package com.picsou.finary.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FinaryOrganization(
    String id,
    String name,
    List<FinaryOrgMember> members
) {}
