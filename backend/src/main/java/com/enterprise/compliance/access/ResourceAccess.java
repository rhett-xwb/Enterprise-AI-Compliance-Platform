package com.enterprise.compliance.access;

import com.enterprise.compliance.auth.PlatformPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component("resourceAccess")
public class ResourceAccess {
    public boolean canReadDepartment(Authentication authentication, long id) {
        if (!(authentication.getPrincipal() instanceof PlatformPrincipal user)) return false;
        return user.roles().contains("ADMIN") || (user.departmentId() != null && user.departmentId() == id);
    }
}
