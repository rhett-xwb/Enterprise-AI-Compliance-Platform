package com.enterprise.compliance;

import com.enterprise.compliance.auth.PlatformPrincipal;
import com.enterprise.compliance.document.*;
import com.enterprise.compliance.user.dto.CreateUserRequest;
import com.enterprise.compliance.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.parser.enabled=false","app.retrieval.enabled=true","app.qa.enabled=true"})
@AutoConfigureMockMvc @Transactional
@EnabledIfEnvironmentVariable(named="TEST_DB_URL",matches=".+")
class QuestionIntegrationTest extends MySqlTestSupport {
    @Autowired JdbcTemplate jdbc; @Autowired DocumentService docs; @Autowired ParsingStore parse;
    @Autowired IndexStore index; @Autowired RetrievalStore retrieval; @Autowired QuestionStore store;
    @Autowired QuestionController questions; @Autowired UserService users; @Autowired ObjectMapper json; @Autowired MockMvc mvc;
    @MockitoBean RetrievalClient client; @MockitoBean QuestionModel model;
    @BeforeEach void configureModelMetadata() { when(model.model()).thenReturn("controlled-test-model"); }
    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }
    private PlatformPrincipal user(String role) {
        String name="qa_"+UUID.randomUUID().toString().replace("-","");
        long id=users.create(new CreateUserRequest(name,"qa-test-password-only",name,null,null)).id();
        jdbc.update("DELETE FROM sys_user_role WHERE user_id=?",id);
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT ?,id FROM sys_role WHERE code=?",id,role);
        return new PlatformPrincipal(id,name,null,List.of(role),role.equals("EMPLOYEE")?List.of("document:read"):List.of("document:read","document:manage"),"test");
    }
    private DocumentService.Detail document(PlatformPrincipal owner) {
        var d=docs.create(new DocumentService.CreateInput(new DocumentService.Metadata("差旅制度",List.of("EMPLOYEE"),List.of()),new DocumentService.VersionInput("v1",LocalDateTime.now().minusDays(1),null)),new MockMultipartFile("file","test.pdf","application/pdf","%PDF-1.7\ntest".getBytes()),owner);
        long id=d.document().id(),v=d.versions().getFirst().id();
        parse.enqueue(id,v,owner,false); var w=parse.claim();
        parse.complete(w,new ParsingStore.Result(w.taskId(),v,w.contentSha256(),"test",List.of(new ParsingStore.Source(0,1,"第 1 页",null,"第一条 出差前必须提交申请。","TEXT",null,List.of()))));
        var work=index.prepare(id,v,owner,false); finish(work); docs.publish(id,v,owner); return d;
    }
    private void finish(IndexStore.Work w) { index.complete(w,json.valueToTree(Map.of("generation",w.generation(),"model",RetrievalClient.MODEL,"keys",w.chunks().stream().map(item -> item.key()).toList()))); }
    private void ranking(PlatformPrincipal employee) {
        var keys=retrieval.keys(employee);
        when(client.call(eq("search"),any())).thenReturn(json.valueToTree(Map.of("model",RetrievalClient.MODEL,"hits",keys.stream().map(k->Map.of("key",k,"score",0.9)).toList())));
        when(model.model()).thenReturn("controlled-test-model");
        doReturn(new QuestionModel.Result("ANSWERED","出差前必须提交申请。[1]",List.of(1))).when(model).generate(anyString(),anyList());
    }
    private QuestionStore.Detail ask(PlatformPrincipal user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of(new SimpleGrantedAuthority("document:read"))));
        return questions.ask(new QuestionController.Ask("出差前怎么办？"),user).data();
    }
    @Test void savesRealSourcesAndSurvivesIndexRebuildWithSnapshot() {
        var owner=user("LEGAL"); var employee=user("EMPLOYEE"); var d=document(owner); ranking(employee);
        var result=ask(employee); assertEquals("ANSWERED",result.status());
        assertEquals("第一条 出差前必须提交申请。",result.citations().getFirst().source().text());
        assertEquals(1,result.citations().getFirst().source().pageNumber());
        long oldChunk=result.citations().getFirst().source().chunkId();
        finish(index.prepare(d.document().id(),d.versions().getFirst().id(),owner,true));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM document_chunk WHERE id=?",Integer.class,oldChunk));
        assertEquals(oldChunk,store.detail(employee,result.id()).citations().getFirst().source().chunkId());
        assertEquals(result.id(),store.history(employee,Long.MAX_VALUE,20).getFirst().id());
        assertTrue(store.history(employee,result.id(),20).isEmpty());
    }
    @Test void unauthorizedHistoryAndSourcesAreHiddenIncludingUncitedEvidence() throws Exception {
        var owner=user("LEGAL"); var employee=user("EMPLOYEE"); var other=user("EMPLOYEE"); var d=document(owner); ranking(employee);
        var result=ask(employee);
        assertThrows(RuntimeException.class,()->store.detail(other,result.id()));
        assertTrue(store.history(other,Long.MAX_VALUE,20).isEmpty());
        docs.update(d.document().id(),new DocumentService.Metadata("差旅制度",List.of("LEGAL"),List.of()),owner);
        var hidden=store.detail(employee,result.id()); assertEquals("RESTRICTED",hidden.status()); assertTrue(hidden.citations().isEmpty()); assertFalse(hidden.answer().contains("提交申请"));
        assertThrows(RuntimeException.class,()->questions.source(result.id(),1,employee));
        docs.update(d.document().id(),new DocumentService.Metadata("差旅制度",List.of("EMPLOYEE"),List.of()),owner);
        docs.retire(d.document().id(),d.versions().getFirst().id(),owner);
        assertEquals("OUTDATED",store.detail(employee,result.id()).citations().getFirst().state());
        assertEquals("INSUFFICIENT",ask(employee).status());
        docs.delete(d.document().id(),owner); assertEquals("RESTRICTED",store.detail(employee,result.id()).status());
    }
    @Test void noEvidenceSkipsModelAndRemoteFailureCanBeRetried() {
        var owner=user("LEGAL"); var employee=user("EMPLOYEE");
        assertEquals("INSUFFICIENT",ask(employee).status()); verify(model,never()).generate(anyString(),anyList());
        document(owner); ranking(employee);
        when(model.generate(anyString(),anyList())).thenThrow(new QuestionModel.Failure("MODEL_TIMEOUT"));
        var failure=ask(employee); assertEquals("FAILED",failure.status()); assertEquals("MODEL_TIMEOUT",failure.errorCode()); assertTrue(failure.citations().isEmpty());
        ranking(employee); assertEquals("ANSWERED",ask(employee).status());
    }
    @Test void changesDuringModelCallDiscardAnswerAndNeverStoreSensitiveSnapshot() {
        var owner=user("LEGAL"); var employee=user("EMPLOYEE"); var d=document(owner); ranking(employee);
        when(model.generate(anyString(),anyList())).thenAnswer(inv->{
            docs.update(d.document().id(),new DocumentService.Metadata("差旅制度",List.of("LEGAL"),List.of()),owner);
            return new QuestionModel.Result("ANSWERED","出差前申请[1]",List.of(1));
        });
        var result=ask(employee); assertEquals("FAILED",result.status()); assertEquals("SOURCE_CHANGED",result.errorCode());
        assertEquals("[]",jdbc.queryForObject("SELECT evidence_json FROM policy_question WHERE id=?",String.class,result.id()));
    }
    @Test void httpRequiresAuthenticationOwnershipAndValidInput() throws Exception {
        var employee=user("EMPLOYEE");
        var auth=authentication(new UsernamePasswordAuthenticationToken(employee,null,List.of(new SimpleGrantedAuthority("document:read"))));
        mvc.perform(get("/api/questions")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/questions").with(java.util.Objects.requireNonNull(auth)).contentType("application/json").content("{\"question\":\" \"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/questions").with(java.util.Objects.requireNonNull(auth)).contentType("application/json").content("{\"question\":\"不存在的制度\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("INSUFFICIENT"));
        mvc.perform(get("/api/questions?size=51").with(java.util.Objects.requireNonNull(auth))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/questions/999999").with(java.util.Objects.requireNonNull(auth))).andExpect(status().isNotFound());
        mvc.perform(post("/api/questions").with(java.util.Objects.requireNonNull(authentication(new UsernamePasswordAuthenticationToken(employee,null,List.of())))).contentType("application/json").content("{\"question\":\"问题\"}")).andExpect(status().isForbidden());
    }
    @Test void conflictPersistsBothSourcesAndUncitedInputStillControlsHistoryAccess() {
        var owner=user("LEGAL"); var employee=user("EMPLOYEE");
        document(owner); document(owner); ranking(employee);
        when(model.generate(anyString(),anyList())).thenReturn(new QuestionModel.Result("CONFLICT","两条依据需要法务核对。[1][2]",List.of(1,2)));
        var conflict=ask(employee); assertEquals("CONFLICT",conflict.status()); assertEquals(2,conflict.citations().size());
        ranking(employee); var single=ask(employee); assertEquals(1,single.citations().size());
        var inputs=json.convertValue(jdbc.queryForObject("SELECT evidence_json FROM policy_question WHERE id=?",String.class,single.id()),String.class);
        assertTrue(inputs.contains("第一条"));
        long citedDocument=single.citations().getFirst().source().documentId();
        long uncitedDocument=java.util.Objects.requireNonNull(jdbc.queryForObject("SELECT id FROM document WHERE owner_id=? AND id<>? AND deleted_at IS NULL",Long.class,owner.id(),citedDocument));
        docs.update(uncitedDocument,new DocumentService.Metadata("差旅制度",List.of("LEGAL"),List.of()),owner);
        assertEquals("RESTRICTED",store.detail(employee,single.id()).status());
    }
}
