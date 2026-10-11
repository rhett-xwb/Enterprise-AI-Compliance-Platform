package com.enterprise.compliance.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank @Pattern(regexp = "[a-zA-Z0-9_-]{3,64}") String username,
        @NotBlank @Size(min = 12, max = 72) String password,
        @NotBlank @Size(max = 100) String displayName,
        @Email @Size(max = 254) String email,
        @Positive Long departmentId) {
    @Override
    public String toString() { return "CreateUserRequest[redacted]"; }
}
