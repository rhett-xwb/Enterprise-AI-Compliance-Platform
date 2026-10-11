package com.enterprise.compliance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "app.bootstrap.enabled=false")
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class DefaultSecurityIntegrationTest extends MySqlTestSupport {
    @Autowired MockMvc mvc;

    @Test
    void healthIsAvailableButAnonymousBusinessRequestsAreBlocked() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk()).andExpect(jsonPath("$.data").value("UP"));
        mvc.perform(get("/api/bootstrap/users")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/bootstrap/users").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "BOOTSTRAP_ADMIN")
    void evenAnAuthenticatedAdministratorCannotOpenDisabledCrud() throws Exception {
        mvc.perform(get("/api/bootstrap/users")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        mvc.perform(delete("/api/bootstrap/users/1")).andExpect(status().isForbidden());
    }
}
