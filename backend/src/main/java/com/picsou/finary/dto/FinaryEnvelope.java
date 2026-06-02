package com.picsou.finary.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FinaryEnvelope<T>(
    T result,
    String message,
    FinaryApiError error
) {}
