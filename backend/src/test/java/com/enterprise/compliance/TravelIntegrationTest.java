package com.enterprise.compliance;

import com.enterprise.compliance.auth.PlatformPrincipal;
import com.enterprise.compliance.document.*;
import com.enterprise.compliance.request.*;
import com.enterprise.compliance.user.dto.CreateUserRequest;
import com.enterprise.compliance.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.parser.enabled=false","app.retrieval.enabled=true","app.qa.enabled=true","app.travel.maximum-amount=10000"})
@AutoConfigureMockMvc @Transactional
@EnabledIfEnvironmentVariable(named="TEST_DB_URL",matches=".+")
class TravelIntegrationTest extends MySqlTestSupport {
    @Autowired TravelStore store; @Autowired TravelReview review; @Autowired UserService users;
    @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc; @Autowired ObjectMapper json;
    @Autowired DocumentService docs; @Autowired ParsingStore parsing; @Autowired IndexStore index; @Autowired RetrievalStore retrieval;
    @MockitoBean RetrievalClient client; @MockitoBean QuestionModel model;
    @Autowired WorkflowStore workflow;
    private Long previousDefinition; private long fixtureDefinition; private PlatformPrincipal fixtureApprover;
    @BeforeEach void init() {
        when(model.model()).thenReturn("controlled-test-model");
        previousDefinition=jdbc.queryForObject("SELECT active_definition_id FROM workflow_publication WHERE code='TRAVEL'",Long.class);
        var approver=user();
        fixtureApprover=approver;
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT ?,id FROM sys_role WHERE code='LEGAL'",approver.id());
        fixtureDefinition=((Number)workflow.publish(approver,new WorkflowStore.Config("测试差旅",List.of(new WorkflowStore.Route(BigDecimal.ZERO,null,List.of(new WorkflowStore.Node("主管",approver.id())))))).get("id")).longValue();
    }
    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            jdbc.update("UPDATE workflow_publication SET active_definition_id=? WHERE code='TRAVEL'",previousDefinition);
            jdbc.update("DELETE FROM workflow_definition WHERE id=?",fixtureDefinition);
            jdbc.update("DELETE FROM sys_user_role WHERE user_id=?",fixtureApprover.id());
            jdbc.update("DELETE FROM sys_user WHERE id=?",fixtureApprover.id());
        }
    }
    private PlatformPrincipal user() {
        String name="travel_"+UUID.randomUUID().toString().replace("-","");
        long id=users.create(new CreateUserRequest(name,"travel-test-only-password",name,null,null)).id();
        return new PlatformPrincipal(id,name,null,List.of("EMPLOYEE"),List.of("approval:submit","document:read"),"test");
    }
    private void identity(PlatformPrincipal u) { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u,null,List.of(new SimpleGrantedAuthority("approval:submit"),new SimpleGrantedAuthority("document:read")))); }
    private TravelStore.Form form(String amount) { return new TravelStore.Form("上海差旅","上海",LocalDate.of(2026,11,1),LocalDate.of(2026,11,3),"客户项目沟通",new BigDecimal(amount)); }
    private MockMultipartFile file() { return new MockMultipartFile("file","receipt.pdf","application/pdf","%PDF-1.7\nfixture".getBytes()); }
    private TravelStore.Detail unknown(PlatformPrincipal u,long id,int version) { identity(u); return review.review(u,id,version); }
    private long policy(PlatformPrincipal u) {
        var owner=new PlatformPrincipal(u.id(),u.username(),null,List.of("ADMIN"),List.of("document:read","document:manage"),"test");
        var d=docs.create(new DocumentService.CreateInput(new DocumentService.Metadata("差旅制度",List.of("EMPLOYEE"),List.of()),new DocumentService.VersionInput("v1",LocalDateTime.now().minusDays(1),null)),file(),owner);
        long id=d.document().id(),v=d.versions().getFirst().id();
        parsing.enqueue(id,v,owner,false); var p=parsing.claim();
        parsing.complete(p,new ParsingStore.Result(p.taskId(),v,p.contentSha256(),"test",List.of(new ParsingStore.Source(0,1,"第 1 页",null,"出差前提交审批申请，并核对预算与附件。","TEXT",null,List.of()))));
        var work=index.prepare(id,v,owner,false);
        index.complete(work,json.valueToTree(Map.of("generation",work.generation(),"model",RetrievalClient.MODEL,"keys",work.chunks().stream().map(IndexStore.Chunk::key).toList())));
        docs.publish(id,v,owner);
        when(client.call(eq("search"),any())).thenReturn(json.valueToTree(Map.of("model",RetrievalClient.MODEL,"hits",retrieval.keys(u).stream().map(k->Map.of("key",k,"score",0.9)).toList())));
        when(model.generate(anyString(),anyList())).thenReturn(new QuestionModel.Result("ANSWERED","请核对预算与附件。[1]",List.of(1)));
        return id;
    }
    @Test void draftCanBeIncompleteButCannotSubmitAndRulesAreDeterministic() {
        var u=user(); var d=store.create(u,new TravelStore.Form("",null,null,null,"",null));
        assertEquals(5,store.rules(d.form()).size()); var r=unknown(u,d.id(),1).reviews().getFirst(); assertEquals("HIGH",r.riskLevel());
        assertThrows(RuntimeException.class,()->store.submit(u,d.id(),1,r.id()));
        assertEquals("CONFIGURED_LIMIT",store.rules(form("10001")).getFirst().code());
        assertTrue(store.rules(form("10000")).isEmpty()); assertFalse(store.rules(form("0")).isEmpty());
        var invalidDate=new TravelStore.Form("标题","北京",LocalDate.of(2026,11,3),LocalDate.of(2026,11,1),"事由",BigDecimal.ONE);
        assertEquals("DATE_ORDER",store.rules(invalidDate).getFirst().code());
    }
    @Test void amountAndAttachmentChangesInvalidateReviewAndOldVersionsAreRejected() throws Exception {
        var u=user(); var d=store.create(u,form("1000")); unknown(u,d.id(),1);
        var updated=store.update(u,d.id(),1,form("2000")); assertEquals("STALE",updated.reviews().getFirst().status());
        assertThrows(RuntimeException.class,()->store.update(u,d.id(),1,form("3000")));
        unknown(u,d.id(),2); var attached=store.attach(u,d.id(),2,file()); assertEquals(3,attached.contentVersion()); assertEquals("STALE",attached.reviews().getFirst().status());
        assertArrayEquals(file().getBytes(),store.download(u,d.id(),attached.attachments().getFirst().id()).bytes());
        unknown(u,d.id(),3); var removed=store.removeAttachment(u,d.id(),attached.attachments().getFirst().id(),3);
        assertEquals(4,removed.contentVersion()); assertTrue(removed.attachments().isEmpty()); assertEquals("STALE",removed.reviews().getFirst().status());
    }
    @Test void submitFreezesCurrentContentAttachmentsReviewAndAudit() {
        var u=user(); var d=store.create(u,form("1234")); d=store.attach(u,d.id(),1,file());
        var r=unknown(u,d.id(),2).reviews().getFirst(); assertEquals("UNKNOWN",r.status()); verify(model,never()).generate(anyString(),anyList());
        long id=d.id(); var result=store.submit(u,id,2,r.id()); assertEquals("IN_REVIEW",result.status());
        var snapshot=json.valueToTree(jdbc.queryForObject("SELECT snapshot_json FROM request_submission WHERE request_id=?",String.class,id));
        assertTrue(snapshot.asText().contains("1234")); assertTrue(snapshot.asText().contains("receipt.pdf"));
        assertEquals(r.id(),jdbc.queryForObject("SELECT analysis_id FROM request_submission WHERE request_id=?",Long.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM approval_action WHERE request_id=? AND action='SUBMIT'",Integer.class,id));
        assertThrows(RuntimeException.class,()->store.submit(u,id,2,r.id())); assertThrows(RuntimeException.class,()->store.update(u,id,2,form("1")));
        assertThrows(RuntimeException.class,()->store.attach(u,id,2,file()));
    }
    @Test void ownerScopeAndHttpValidationProtectEveryMutation() throws Exception {
        var u=user(); var other=user(); var d=store.create(u,form("100")); var a=store.attach(u,d.id(),1,file()).attachments().getFirst();
        assertTrue(store.list(other,Long.MAX_VALUE,20).isEmpty()); assertThrows(RuntimeException.class,()->store.detail(other,d.id()));
        assertThrows(RuntimeException.class,()->store.download(other,d.id(),a.id())); assertThrows(RuntimeException.class,()->store.removeAttachment(other,d.id(),a.id(),2));
        var auth=authentication(new UsernamePasswordAuthenticationToken(u,null,List.of(new SimpleGrantedAuthority("approval:submit"))));
        mvc.perform(get("/api/travel-requests")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/travel-requests").with(auth).contentType("application/json").content("{\"amount\":-1}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/travel-requests?size=51").with(auth)).andExpect(status().isBadRequest());
        mvc.perform(put("/api/travel-requests/"+d.id()).with(auth).contentType("application/json").content("{\"contentVersion\":1,\"form\":{}}")).andExpect(status().isConflict());
        mvc.perform(get("/api/travel-requests").with(authentication(new UsernamePasswordAuthenticationToken(u,null,List.of())))).andExpect(status().isForbidden());
        assertThrows(RuntimeException.class,()->store.attach(u,d.id(),2,new MockMultipartFile("file","bad.exe","text/plain","x".getBytes())));
    }
    @Test void authorizedPolicyAdviceIsCitedAndPermissionChangeHidesIt() {
        var u=user(); long doc=policy(u); var d=store.create(u,form("100")); identity(u);
        var result=review.review(u,d.id(),1); var r=result.reviews().getFirst(); assertEquals("REVIEWED",r.status()); assertEquals(List.of(1),r.citations()); assertTrue(r.evidence().getFirst().text().contains("预算"));
        jdbc.update("DELETE FROM document_role_access WHERE document_id=?",doc);
        var hidden=store.detail(u,d.id()).reviews().getFirst(); assertEquals("RESTRICTED",hidden.status()); assertTrue(hidden.evidence().isEmpty()); assertFalse(hidden.advice().contains("预算"));
        assertThrows(RuntimeException.class,()->store.submit(u,d.id(),1,r.id()));
    }
    @Test void remoteFailureIsRecordedAndRetryCanRecover() {
        var u=user(); policy(u); var d=store.create(u,form("100")); identity(u);
        when(model.generate(anyString(),anyList())).thenThrow(new QuestionModel.Failure("MODEL_TIMEOUT"));
        var failure=review.review(u,d.id(),1).reviews().getFirst(); assertEquals("FAILED",failure.status()); assertEquals("MODEL_TIMEOUT",failure.errorCode());
        assertThrows(RuntimeException.class,()->store.submit(u,d.id(),1,failure.id()));
        doReturn(new QuestionModel.Result("ANSWERED","核对预算[1]",List.of(1))).when(model).generate(anyString(),anyList());
        assertEquals("REVIEWED",review.review(u,d.id(),1).reviews().getFirst().status());
    }
    @Test void changeDuringModelCallCannotAttachReviewToNewContent() {
        var u=user(); policy(u); var d=store.create(u,form("100")); identity(u);
        when(model.generate(anyString(),anyList())).thenAnswer(inv->{ store.update(u,d.id(),1,form("200")); return new QuestionModel.Result("ANSWERED","检查预算[1]",List.of(1)); });
        assertThrows(RuntimeException.class,()->review.review(u,d.id(),1)); assertTrue(store.detail(u,d.id()).reviews().isEmpty());
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void concurrentUpdateAndSubmitCannotBothCommit() throws Exception {
        var u=user(); var d=store.create(u,form("100")); var r=unknown(u,d.id(),1).reviews().getFirst();
        try {
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var update=pool.submit(()->{ gate.await(); try { store.update(u,d.id(),1,form("200")); return true; } catch(com.enterprise.compliance.common.BusinessException ex) { return false; } });
            var submit=pool.submit(()->{ gate.await(); try { store.submit(u,d.id(),1,r.id()); return true; } catch(com.enterprise.compliance.common.BusinessException ex) { return false; } });
            gate.countDown(); assertNotEquals(update.get(20,TimeUnit.SECONDS),submit.get(20,TimeUnit.SECONDS));
        }
        var finalState=store.detail(u,d.id());
        if(finalState.status().equals("IN_REVIEW")) assertEquals(new BigDecimal("100"),finalState.form().amount());
        else { assertEquals(2,finalState.contentVersion()); assertEquals("STALE",finalState.reviews().getFirst().status()); }
        } finally {
            jdbc.update("DELETE t FROM approval_task t JOIN workflow_instance i ON i.id=t.instance_id WHERE i.request_id=?",d.id());
            jdbc.update("DELETE FROM workflow_instance WHERE request_id=?",d.id());
            jdbc.update("DELETE FROM request_submission WHERE request_id=?",d.id());
            jdbc.update("DELETE FROM approval_action WHERE request_id=?",d.id());
            jdbc.update("DELETE FROM ai_analysis_record WHERE request_id=?",d.id());
            jdbc.update("DELETE FROM approval_request WHERE id=? AND applicant_id=?",d.id(),u.id());
            jdbc.update("DELETE FROM sys_user_role WHERE user_id=?",u.id());
            jdbc.update("DELETE FROM sys_user WHERE id=?",u.id());
        }
    }
}
