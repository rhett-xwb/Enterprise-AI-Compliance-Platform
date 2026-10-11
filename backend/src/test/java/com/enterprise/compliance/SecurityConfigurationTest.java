package com.enterprise.compliance;

import com.enterprise.compliance.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SecurityConfigurationTest {
    private final SecurityConfig config = new SecurityConfig();

    @Test
    void bootstrapCannotBindToAllInterfacesOrUseAnEmptyPassword() {
        assertThrows(IllegalStateException.class, () -> config.bootstrapUserDetailsService(
                true, "admin", "test-password-long-enough", "0.0.0.0", config.passwordEncoder()));
        assertThrows(IllegalStateException.class, () -> config.bootstrapUserDetailsService(
                true, "admin", "", "127.0.0.1", config.passwordEncoder()));
        assertThrows(IllegalStateException.class, () -> config.bootstrapUserDetailsService(
                true, "admin", "密".repeat(30), "127.0.0.1", config.passwordEncoder()));
    }
}
