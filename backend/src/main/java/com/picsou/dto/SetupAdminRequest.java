package com.picsou.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payload submitted by the setup wizard's Admin step. The server hashes
 * {@code password} with the shared {@code BCryptPasswordEncoder} before
 * persisting — callers never see or store the plaintext.
 */
public record SetupAdminRequest(
    @NotBlank @Size(min = 3, max = 50)
    @Pattern(regexp = "[a-zA-Z0-9._-]+",
             message = "Username may only contain letters, digits, dots, underscores and hyphens")
    String username,

    @NotBlank @Size(min = 8, max = 128) String password,

    @Size(max = 80) String displayName,

    @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "Avatar color must be a hex color like #6366f1")
    String avatarColor
) {}
