package com.enterprise.compliance.user.dto;

import com.enterprise.compliance.user.entity.UserEntity;
import java.time.LocalDateTime;

public record UserResponse(Long id, String username, String displayName, String email,
        Long departmentId, Boolean enabled, LocalDateTime createdAt, LocalDateTime updatedAt) {
    public static UserResponse from(UserEntity user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getDisplayName(),
                user.getEmail(), user.getDepartmentId(), user.getEnabled(),
                user.getCreatedAt(), user.getUpdatedAt());
    }
}
