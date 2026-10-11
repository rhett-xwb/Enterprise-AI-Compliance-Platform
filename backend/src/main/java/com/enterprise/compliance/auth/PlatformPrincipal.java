package com.enterprise.compliance.auth;

import java.util.List;

public record PlatformPrincipal(long id, String username, Long departmentId,
        List<String> roles, List<String> permissions, String sessionId) {}
