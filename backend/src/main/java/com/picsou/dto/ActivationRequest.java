package com.picsou.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.True;

public record ActivationRequest(
    @NotBlank @Size(min = 8, max = 128) String password,
    @True boolean acknowledgedWarning
) {}
