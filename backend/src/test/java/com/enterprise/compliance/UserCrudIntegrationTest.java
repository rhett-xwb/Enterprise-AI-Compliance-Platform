package com.enterprise.compliance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.NonNull;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static java.util.Objects.requireNonNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.bootstrap.enabled=true", "app.bootstrap.username=test-bootstrap",
        "app.bootstrap.password=test-bootstrap-password-only", "server.address=127.0.0.1"})
@AutoConfigureMockMvc
@Transactional
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class UserCrudIntegrationTest extends MySqlTestSupport {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;

    private static final String PASSWORD = "user-test-password-only";
    private static final String BODY = """
            {"username":"test_employee","password":"user-test-password-only","displayName":"测试员工","email":"employee@example.test"}
            """;

    private @NonNull RequestPostProcessor admin() {
        return requireNonNull(httpBasic("test-bootstrap", "test-bootstrap-password-only"));
    }

    private long createUser() throws Exception {
        String response = mvc.perform(post("/api/bootstrap/users").with(admin())
                .contentType("application/json").content(BODY))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(header().exists("X-Request-Id"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(response.contains(PASSWORD));
        return json.readTree(response).path("data").path("id").asLong();
    }

    @Test
    void crudHashesPasswordsAndDeletesWithoutLosingHistory() throws Exception {
        long id = createUser();
        String hash = jdbc.queryForObject("SELECT password_hash FROM sys_user WHERE id=?", String.class, id);
        assertNotEquals(PASSWORD, hash);
        assertTrue(encoder.matches(PASSWORD, hash));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=? AND r.code='EMPLOYEE'", Integer.class, id));
        mvc.perform(get("/api/bootstrap/users/{id}", id).with(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value("test_employee"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
        mvc.perform(get("/api/bootstrap/users?page=1&size=10").with(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(put("/api/bootstrap/users/{id}", id).with(admin()).contentType("application/json")
                .content("{\"displayName\":\"更新员工\",\"email\":null,\"departmentId\":null,\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false));
        assertNull(jdbc.queryForObject("SELECT email FROM sys_user WHERE id=?", String.class, id));
        assertEquals(hash, jdbc.queryForObject("SELECT password_hash FROM sys_user WHERE id=?", String.class, id));
        mvc.perform(delete("/api/bootstrap/users/{id}", id).with(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get("/api/bootstrap/users/{id}", id).with(admin())).andExpect(status().isNotFound());
        mvc.perform(get("/api/bootstrap/users").with(admin())).andExpect(jsonPath("$.data.total").value(0));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=? AND deleted_at IS NOT NULL AND enabled=false", Integer.class, id));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_role WHERE user_id=?", Integer.class, id));
    }

    @Test
    void unauthorizedAndInvalidCredentialsCannotReadOrWrite() throws Exception {
        mvc.perform(get("/api/bootstrap/users")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        mvc.perform(post("/api/bootstrap/users").contentType("application/json").content(BODY)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/bootstrap/users").with(requireNonNull(httpBasic("test-bootstrap", "wrong")))).andExpect(status().isUnauthorized());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class));
    }

    @Test
    void conflictsInvalidInputsAndUnknownFieldsHaveSafeErrors() throws Exception {
        createUser();
        mvc.perform(post("/api/bootstrap/users").with(admin()).contentType("application/json").content(BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409))
                .andExpect(content().string(requireNonNull(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("password_hash")))));
        mvc.perform(post("/api/bootstrap/users").with(admin()).contentType("application/json").content(requireNonNull(BODY.replace(PASSWORD, "short"))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/bootstrap/users").with(admin()).contentType("application/json").content(requireNonNull(BODY.replace("user-test-password-only", "密".repeat(30)))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/bootstrap/users").with(admin()).contentType("application/json").content("{invalid-json"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/bootstrap/users?size=101").with(admin())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/bootstrap/users/-1").with(admin())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/bootstrap/users/999999").with(admin())).andExpect(status().isNotFound());
        JsonNode payload = json.readTree(BODY);
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload).put("role", "ADMIN");
        mvc.perform(post("/api/bootstrap/users").with(admin()).contentType("application/json").content(requireNonNull(payload.toString())))
                .andExpect(status().isBadRequest());
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload).remove("role");
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload).put("departmentId", 999999);
        mvc.perform(post("/api/bootstrap/users").with(admin()).contentType("application/json").content(requireNonNull(payload.toString())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void migrationsSeedMetadataAndRejectCrossDocumentVersionPointers() throws Exception {
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM sys_role", Integer.class));
        assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM sys_permission", Integer.class));
        long owner = createUser();
        jdbc.update("INSERT INTO document(id,title,knowledge_domain,owner_id) VALUES (901,'A','POLICY',?),(902,'B','POLICY',?)", owner, owner);
        jdbc.update("INSERT INTO document_version(id,document_id,version_no,storage_key,original_filename,content_sha256,content_type,size_bytes,creator_id) VALUES(901,901,'v1','a.pdf','a.pdf',?,'application/pdf',1,?)", "a".repeat(64), owner);
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE document SET current_version_id=901 WHERE id=902"));
        var checkFailure = assertThrows(org.springframework.jdbc.UncategorizedSQLException.class,
                () -> jdbc.update("INSERT INTO ai_citation(chunk_id,quoted_text) VALUES(999,'invalid')"));
        var sqlException = requireNonNull(checkFailure.getSQLException());
        assertEquals(3819, sqlException.getErrorCode());
        assertTrue(requireNonNull(sqlException.getMessage()).contains("ck_citation_owner"));
    }
}
