package com.enterprise.compliance;

import com.enterprise.compliance.auth.AuthService;
import com.enterprise.compliance.user.dto.CreateUserRequest;
import com.enterprise.compliance.user.dto.UpdateUserRequest;
import com.enterprise.compliance.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.NonNull;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static java.util.Objects.requireNonNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="app.bootstrap.enabled=false")
@AutoConfigureMockMvc
@Transactional
@EnabledIfEnvironmentVariable(named="TEST_DB_URL",matches=".+")
class AuthIntegrationTest extends MySqlTestSupport {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserService users;
    @Autowired AuthService auth;
    @Autowired JwtEncoder encoder;
    @Autowired JwtDecoder decoder;
    @Autowired ObjectMapper json;
    private static final String PASSWORD="account-test-password-only";

    private @NonNull String writeJson(Object payload) throws Exception {
        return requireNonNull(json.writeValueAsString(payload));
    }

    private long account(String username,String role,Long department) {
        long id=users.create(new CreateUserRequest(username,PASSWORD,username,null,department)).id();
        jdbc.update("DELETE FROM sys_user_role WHERE user_id=?",id);
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT ?,id FROM sys_role WHERE code=?",id,role);
        return id;
    }

    private String login(String username) throws Exception {
        String response=mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(writeJson(java.util.Map.of("username",username,"password",PASSWORD))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(900))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("data").path("accessToken").asText();
    }

    @Test
    void loginLogoutAndFailureResponses() throws Exception {
        account("employee","EMPLOYEE",null);
        mvc.perform(post("/api/auth/login").contentType("application/json")
                .content("{\"username\":\"employee\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("Invalid username or password"));
        mvc.perform(post("/api/auth/login").contentType("application/json")
                .content("{\"username\":\"unknown\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("Invalid username or password"));
        String first=login("employee"), second=login("employee");
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+first))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.roles[0]").value("EMPLOYEE"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/logout").header("Authorization","Bearer "+first)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+first)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+second)).andExpect(status().isOk());
    }

    @Test
    void rolesAndPermissionsControlUsersAndChangeWithoutRelogin() throws Exception {
        account("administrator","ADMIN",null);
        long employeeId=account("employee","EMPLOYEE",null);
        account("legal_user","LEGAL",null);
        String admin=login("administrator"), employee=login("employee"), legal=login("legal_user");
        mvc.perform(get("/api/users").header("Authorization","Bearer "+admin)).andExpect(status().isOk());
        mvc.perform(get("/api/users").header("Authorization","Bearer "+employee)).andExpect(status().isForbidden());
        mvc.perform(get("/api/users").header("Authorization","Bearer "+legal)).andExpect(status().isForbidden());
        mvc.perform(put("/api/users/{id}/roles",employeeId).header("Authorization","Bearer "+employee)
                .contentType("application/json").content("{\"roles\":[\"ADMIN\"]}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/users/{id}/roles",employeeId).header("Authorization","Bearer "+admin)
                .contentType("application/json").content("{\"roles\":[\"LEGAL\"]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+employee))
                .andExpect(jsonPath("$.data.roles[0]").value("LEGAL"));
        // URL grants authority, but method-level ADMIN check must still deny LEGAL.
        jdbc.update("INSERT INTO sys_role_permission(role_id,permission_id) SELECT r.id,p.id FROM sys_role r,sys_permission p WHERE r.code='LEGAL' AND p.code='user:manage'");
        mvc.perform(get("/api/users").header("Authorization","Bearer "+legal))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        long adminId=requireNonNull(jdbc.queryForObject("SELECT id FROM sys_user WHERE username='administrator'",Long.class));
        mvc.perform(put("/api/users/{id}/roles",adminId).header("Authorization","Bearer "+admin)
                .contentType("application/json").content("{\"roles\":[\"EMPLOYEE\"]}"))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/users/{id}",adminId).header("Authorization","Bearer "+admin))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/users/{id}",adminId).header("Authorization","Bearer "+admin)
                .contentType("application/json")
                .content(writeJson(new UpdateUserRequest("administrator",null,null,false))))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.roles[0]").value("ADMIN"));
    }

    @Test
    void disabledDeletedAndReenabledAccountsCannotReuseOldSessions() throws Exception {
        long id=account("employee","EMPLOYEE",null);
        String token=login("employee");
        users.update(id,new UpdateUserRequest("employee",null,null,false));
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(writeJson(java.util.Map.of("username","employee","password",PASSWORD))))
                .andExpect(status().isUnauthorized());
        users.update(id,new UpdateUserRequest("employee",null,null,true));
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        String newToken=login("employee");
        users.delete(id);
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+newToken)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(writeJson(java.util.Map.of("username","employee","password",PASSWORD))))
                .andExpect(status().isUnauthorized());
    }

    private String sign(String userId,String session,String issuer,Instant expires) {
        var builder=JwtClaimsSet.builder().issuer(issuer).subject(userId).issuedAt(Instant.now().minusSeconds(120)).claim("jti",session);
        if(expires!=null) builder.expiresAt(expires);
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),builder.build())).getTokenValue();
    }

    @Test
    void rejectsExpiredTamperedWrongIssuerAndMissingClaimsTokens() throws Exception {
        account("employee","EMPLOYEE",null);
        String valid=login("employee");
        var decoded=decoder.decode(valid);
        String[] parts=valid.split("\\.");
        String tampered=parts[0]+"."+parts[1]+"."+(parts[2].charAt(0)=='a'?'b':'a')+parts[2].substring(1);
        String[] invalid={"not-a-jwt",tampered,
                sign(decoded.getSubject(),decoded.getId(),"compliance-platform",Instant.now().minusSeconds(60)),
                sign(decoded.getSubject(),decoded.getId(),"other-issuer",Instant.now().plusSeconds(60)),
                sign(decoded.getSubject(),decoded.getId(),"compliance-platform",null),
                sign(decoded.getSubject(),UUID.randomUUID().toString(),"compliance-platform",Instant.now().plusSeconds(60))};
        for(String token:invalid) mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void departmentAndRequestScopesPreventAccessToOtherEmployeesData() throws Exception {
        jdbc.update("INSERT INTO sys_department(id,code,name) VALUES(801,'A','A'),(802,'B','B')");
        long alice=account("alice_user","EMPLOYEE",801L);
        long bob=account("bob_user","EMPLOYEE",801L);
        long legalId=account("legal_user","LEGAL",802L);
        account("administrator","ADMIN",null);
        jdbc.update("INSERT INTO approval_request(id,applicant_id,request_type,title,content_json) VALUES(801,?,'TRAVEL','Alice request','{}'),(802,?,'TRAVEL','Bob request','{}')",alice,bob);
        String aliceToken=login("alice_user"), legalToken=login("legal_user"), adminToken=login("administrator");
        mvc.perform(get("/api/departments/801").header("Authorization","Bearer "+aliceToken)).andExpect(status().isOk());
        mvc.perform(get("/api/departments/802").header("Authorization","Bearer "+aliceToken)).andExpect(status().isForbidden());
        mvc.perform(get("/api/departments/802").header("Authorization","Bearer "+adminToken)).andExpect(status().isOk());
        mvc.perform(get("/api/approval-requests/801").header("Authorization","Bearer "+aliceToken)).andExpect(status().isOk());
        mvc.perform(get("/api/approval-requests/802").header("Authorization","Bearer "+aliceToken)).andExpect(status().isNotFound());
        mvc.perform(get("/api/approval-requests/801").header("Authorization","Bearer "+legalToken)).andExpect(status().isNotFound());
        jdbc.update("INSERT INTO workflow_definition(id,code,version_no,name,config_json,creator_id) VALUES(801,'test',1,'test','{}',?)",legalId);
        jdbc.update("INSERT INTO workflow_instance(id,request_id,definition_id,request_content_version,request_snapshot,status) VALUES(801,801,801,1,'{}','ACTIVE')");
        jdbc.update("INSERT INTO approval_task(instance_id,node_key,assignee_id) VALUES(801,'LEGAL',?)",legalId);
        mvc.perform(get("/api/approval-requests/801").header("Authorization","Bearer "+legalToken)).andExpect(status().isOk());
        jdbc.update("UPDATE approval_task SET status='COMPLETED' WHERE instance_id=801");
        mvc.perform(get("/api/approval-requests/801").header("Authorization","Bearer "+legalToken)).andExpect(status().isNotFound());
    }
}
