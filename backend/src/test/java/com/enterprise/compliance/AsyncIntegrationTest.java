package com.enterprise.compliance;

import com.enterprise.compliance.async.*;
import com.enterprise.compliance.auth.*;
import com.enterprise.compliance.document.*;
import com.enterprise.compliance.user.dto.CreateUserRequest;
import com.enterprise.compliance.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.async.enabled=false","app.parser.enabled=false","app.retrieval.enabled=false"})
@AutoConfigureMockMvc @Transactional
@EnabledIfEnvironmentVariable(named="TEST_DB_URL",matches=".+")
class AsyncIntegrationTest extends MySqlTestSupport {
    @org.junit.jupiter.api.BeforeEach void cacheMissByDefault() { doReturn(null).when(cache).get(anyLong(),anyString()); }
    @Autowired JdbcTemplate jdbc; @Autowired AsyncJobs jobs; @Autowired AsyncDocuments async;
    @Autowired AsyncProcessor processor; @Autowired DocumentService docs; @Autowired ParsingStore parsing;
    @Autowired IndexStore index; @Autowired UserService users; @Autowired AuthService auth;
    @Autowired ObjectMapper json; @Autowired MockMvc mvc; @Autowired PlatformTransactionManager tx;
    @MockitoBean ParserClient parser; @MockitoBean RetrievalClient retrieval; @MockitoBean SourceCache cache;
    private PlatformPrincipal user(String role) {
        String name="async_"+UUID.randomUUID().toString().replace("-","");
        long id=users.create(new CreateUserRequest(name,"async-test-password-only",name,null,null)).id();
        jdbc.update("DELETE FROM sys_user_role WHERE user_id=?",id);
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT ?,id FROM sys_role WHERE code=?",id,role);
        return new PlatformPrincipal(id,name,null,List.of(role),List.of("document:read","document:manage"),"test");
    }
    private DocumentService.Detail document(PlatformPrincipal owner) {
        return docs.create(new DocumentService.CreateInput(new DocumentService.Metadata("阶段十任务",List.of("EMPLOYEE"),List.of()),new DocumentService.VersionInput("v1",LocalDateTime.now().minusDays(1),null)),new MockMultipartFile("file","test.pdf","application/pdf","%PDF-1.7\ntest".getBytes()),owner);
    }
    private void successfulParser() {
        doAnswer(a->{ var w=a.<ParsingStore.Work>getArgument(0); return new ParsingStore.Result(w.taskId(),w.documentVersionId(),w.contentSha256(),"contract-parser",List.of(new ParsingStore.Source(0,1,"第 1 页",null,"第一条 出差需审批。","TEXT",null,List.of()))); }).when(parser).parse(any());
    }
    private AsyncJobs.Message message(long version,String type) {
        return jdbc.query("SELECT id,message_token FROM document_async_job WHERE document_version_id=? AND task_type=? ORDER BY id DESC LIMIT 1",(r,n)->new AsyncJobs.Message(r.getLong(1),r.getString(2)),version,type).getFirst();
    }
    private String jobStatus(long id) { return jdbc.queryForObject("SELECT status FROM document_async_job WHERE id=?",String.class,id); }
    private void due(long id) { jdbc.update("UPDATE document_async_job SET next_attempt_at=? WHERE id=?",LocalDateTime.now().minusSeconds(1),id); }
    @Test void repeatedMessagesAndRequestsProduceOneParse() {
        var u=user("LEGAL"); var d=document(u); long v=d.versions().getFirst().id(); successfulParser();
        var t=async.parse(d.document().id(),v,u,false); assertEquals(t.id(),async.parse(d.document().id(),v,u,false).id());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM document_async_job WHERE document_version_id=?",Integer.class,v));
        var m=message(v,"PARSE"); processor.process(m); processor.process(m);
        verify(parser,times(1)).parse(any()); assertEquals("SUCCEEDED",jobStatus(m.id()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM document_parse_source WHERE document_version_id=?",Integer.class,v));
    }
    @Test void rolledBackBusinessTransactionLeavesNeitherTaskNorOutbox() {
        var template=new TransactionTemplate(tx); template.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        long[] version={0};
        template.execute(s->{ var u=user("LEGAL"); var d=document(u); version[0]=d.versions().getFirst().id(); async.parse(d.document().id(),version[0],u,false); s.setRollbackOnly(); return null; });
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM document_async_job WHERE document_version_id=?",Integer.class,version[0]));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM document_processing_task WHERE document_version_id=?",Integer.class,version[0]));
    }
    @Test void brokerFailureAndUnconfirmedDeliveryRemainRecoverable() {
        var u=user("LEGAL"); var d=document(u); long v=d.versions().getFirst().id(); async.parse(d.document().id(),v,u,false);
        var m=message(v,"PARSE"); assertTrue(jobs.dispatchable().contains(m)); jobs.deliveryFailed(m);
        assertEquals("PENDING",jobStatus(m.id())); due(m.id()); assertTrue(jobs.dispatchable().contains(m));
        jobs.dispatched(m); assertFalse(jobs.dispatchable().contains(m));
        jdbc.update("UPDATE document_async_job SET dispatched_at=? WHERE id=?",LocalDateTime.now().minusMinutes(2),m.id());
        assertTrue(jobs.dispatchable().contains(m));
    }
    @Test void parserOutageRecoversAndOldMessageCannotConsumeRetry() {
        var u=user("LEGAL"); var d=document(u); long v=d.versions().getFirst().id(); async.parse(d.document().id(),v,u,false);
        when(parser.parse(any())).thenThrow(new ParserClient.Failure("PARSER_UNAVAILABLE"));
        var old=message(v,"PARSE"); processor.process(old); assertEquals("PENDING",jobStatus(old.id()));
        due(old.id()); successfulParser(); processor.process(old); verify(parser,times(1)).parse(any());
        processor.process(message(v,"PARSE")); assertEquals("SUCCEEDED",jobStatus(old.id()));
        assertEquals(2,parsing.status(d.document().id(),v,u).attempts());
    }
    @Test void finiteAutomaticAndManualRetriesAndPermanentFailure() {
        var u=user("LEGAL"); var d=document(u); long v=d.versions().getFirst().id(); async.parse(d.document().id(),v,u,false);
        when(parser.parse(any())).thenThrow(new ParserClient.Failure("PARSER_UNAVAILABLE"));
        for(int i=0;i<3;i++) { var m=message(v,"PARSE"); due(m.id()); processor.process(m); }
        long id=message(v,"PARSE").id(); assertEquals("FAILED",jobStatus(id));
        for(int i=0;i<2;i++) { jobs.retry(id); processor.process(message(v,"PARSE")); assertEquals("FAILED",jobStatus(id)); }
        assertThrows(com.enterprise.compliance.common.BusinessException.class,()->jobs.retry(id));
        assertThrows(com.enterprise.compliance.common.BusinessException.class,()->async.parse(d.document().id(),v,u,true));
        var second=document(u); long v2=second.versions().getFirst().id(); async.parse(second.document().id(),v2,u,false);
        doThrow(new ParserClient.Failure("PDF_ENCRYPTED")).when(parser).parse(any()); processor.process(message(v2,"PARSE")); assertEquals("FAILED",jobStatus(message(v2,"PARSE").id()));
    }
    @Test void workerLeaseRecoveryRejectsLateParserResultAndAdoptsCommittedSuccess() {
        var u=user("LEGAL"); var d=document(u); long v=d.versions().getFirst().id(); async.parse(d.document().id(),v,u,false);
        var m=message(v,"PARSE"); var job=jobs.claim(m); var old=parsing.claim(json.valueToTree(jsonRead(job.payload())).path("taskId").asLong());
        jdbc.update("UPDATE document_async_job SET lease_until=? WHERE id=?",LocalDateTime.now().minusMinutes(1),job.id()); jobs.recover();
        parsing.complete(old,new ParsingStore.Result(old.taskId(),v,old.contentSha256(),"test",List.of(new ParsingStore.Source(0,1,"第 1 页",null,"旧响应","TEXT",null,List.of()))));
        assertTrue(parsing.sources(d.document().id(),v,u).isEmpty()); due(m.id()); successfulParser(); processor.process(message(v,"PARSE")); assertEquals("SUCCEEDED",jobStatus(m.id()));
        // Simulate crash after the business result committed but before ACK/job finalization.
        jdbc.update("UPDATE document_async_job SET status='PROCESSING',run_token=?,lease_until=? WHERE id=?",UUID.randomUUID().toString(),LocalDateTime.now().minusMinutes(1),m.id()); jobs.recover(); assertEquals("SUCCEEDED",jobStatus(m.id())); verify(parser,times(1)).parse(any());
        // Upgrade from the old polling mode: no outbox existed for an interrupted task.
        var legacy=document(u); long legacyVersion=legacy.versions().getFirst().id();
        var legacyTask=parsing.enqueue(legacy.document().id(),legacyVersion,u,false); var legacyWork=parsing.claim(legacyTask.id());
        assertFalse(jobs.active(legacyVersion,"PARSE"));
        jdbc.update("UPDATE document_processing_task SET lease_until=? WHERE id=?",LocalDateTime.now().minusMinutes(1),legacyTask.id());
        jobs.dispatchable(); var adopted=message(legacyVersion,"PARSE"); processor.process(adopted);
        parsing.complete(legacyWork,new ParsingStore.Result(legacyTask.id(),legacyVersion,legacyWork.contentSha256(),"old",List.of(new ParsingStore.Source(0,1,"第 1 页",null,"升级前旧响应","TEXT",null,List.of()))));
        assertEquals("SUCCEEDED",jobStatus(adopted.id())); assertEquals("第一条 出差需审批。",parsing.sources(legacy.document().id(),legacyVersion,u).getFirst().text());
        var exhausted=document(u); long exhaustedVersion=exhausted.versions().getFirst().id();
        var exhaustedTask=parsing.enqueue(exhausted.document().id(),exhaustedVersion,u,false);
        jdbc.update("UPDATE document_processing_task SET attempts=5 WHERE id=?",exhaustedTask.id());
        jobs.dispatchable(); var exhaustedMessage=message(exhaustedVersion,"PARSE"); processor.process(exhaustedMessage);
        assertEquals("FAILED",jobStatus(exhaustedMessage.id()));
        assertThrows(com.enterprise.compliance.common.BusinessException.class,()->jobs.retry(exhaustedMessage.id()));
    }
    private Object jsonRead(String raw) { try { return json.readTree(raw); } catch(Exception ex) { throw new RuntimeException(ex); } }
    @Test void indexRetryKeepsGenerationAndVectorKeysAndDuplicateMessagesDoNotReindex() {
        var u=user("LEGAL"); var d=document(u); long v=d.versions().getFirst().id(); successfulParser(); async.parse(d.document().id(),v,u,false); processor.process(message(v,"PARSE"));
        var state=async.index(d.document().id(),v,u,false); assertEquals(state.generation(),async.index(d.document().id(),v,u,true).generation());
        when(retrieval.call(eq("index"),any())).thenThrow(new RuntimeException("service down")); var old=message(v,"INDEX"); processor.process(old); due(old.id());
        doAnswer(a->{ var body=a.<Map<String,Object>>getArgument(1); var chunks=(List<IndexStore.Chunk>)body.get("chunks"); return json.valueToTree(Map.of("generation",body.get("generation"),"model",RetrievalClient.MODEL,"keys",chunks.stream().map(IndexStore.Chunk::key).toList())); }).when(retrieval).call(eq("index"),any());
        var retry=message(v,"INDEX"); processor.process(retry); processor.process(retry); verify(retrieval,times(2)).call(eq("index"),any());
        assertEquals("SUCCEEDED",jobStatus(old.id())); assertEquals(state.generation(),index.state(d.document().id(),v,u).generation());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM document_chunk WHERE document_version_id=?",Integer.class,v));
        assertEquals("READY",jdbc.queryForObject("SELECT processing_status FROM document_version WHERE id=?",String.class,v));
    }
    @Test void deletionPreventsExternalCallAndCachedSourcesAlwaysRequireCurrentGovernance() throws Exception {
        var u=user("LEGAL"); var other=user("LEGAL"); var d=document(u); long v=d.versions().getFirst().id(); async.parse(d.document().id(),v,u,false); docs.delete(d.document().id(),u); processor.process(message(v,"PARSE")); verifyNoInteractions(parser);
        var second=document(u); long v2=second.versions().getFirst().id(); successfulParser(); async.parse(second.document().id(),v2,u,false); processor.process(message(v2,"PARSE"));
        var sources=List.of(new ParsingStore.Source(0,1,"第 1 页",null,"缓存正文","TEXT",null,List.of())); when(cache.get(eq(v2),anyString())).thenReturn(sources);
        assertEquals("缓存正文",parsing.sources(second.document().id(),v2,u).getFirst().text());
        clearInvocations(cache); assertThrows(com.enterprise.compliance.common.BusinessException.class,()->parsing.sources(second.document().id(),v2,other)); verifyNoInteractions(cache);
        String ownerToken=auth.login(u.username(),"async-test-password-only").accessToken();
        jdbc.update("DELETE FROM sys_user_role WHERE user_id=?",u.id());
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT ?,id FROM sys_role WHERE code='EMPLOYEE'",u.id());
        mvc.perform(get("/api/documents/{id}/versions/{v}/sources",second.document().id(),v2).header("Authorization","Bearer "+ownerToken)).andExpect(status().isForbidden()); verifyNoInteractions(cache);
        docs.delete(second.document().id(),u); assertThrows(com.enterprise.compliance.common.BusinessException.class,()->parsing.sources(second.document().id(),v2,u)); verifyNoInteractions(cache);
        String token=auth.login(other.username(),"async-test-password-only").accessToken();
        mvc.perform(get("/api/document-tasks").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/document-tasks/metrics").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
    }
    @Test void administratorSeesSafeLedgerWithoutPayloadOrTokens() throws Exception {
        var u=user("ADMIN"); var d=document(u); long v=d.versions().getFirst().id(); async.parse(d.document().id(),v,u,false);
        String token=auth.login(u.username(),"async-test-password-only").accessToken();
        mvc.perform(get("/api/document-tasks").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].payload_json").doesNotExist()).andExpect(jsonPath("$.data[0].run_token").doesNotExist());
    }
}
