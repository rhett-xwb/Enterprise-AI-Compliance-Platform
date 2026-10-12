package com.enterprise.compliance;

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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"app.parser.enabled=false","app.retrieval.enabled=false"}) @Transactional
@EnabledIfEnvironmentVariable(named="TEST_DB_URL",matches=".+")
class RetrievalIntegrationTest extends MySqlTestSupport {
    @Autowired JdbcTemplate jdbc; @Autowired DocumentService docs; @Autowired ParsingStore parse;
    @Autowired IndexStore index; @Autowired RetrievalStore retrieval; @Autowired UserService users; @Autowired ObjectMapper json;
    private PlatformPrincipal user(String role) {
        String name="rag_"+UUID.randomUUID().toString().replace("-","");
        long id=users.create(new CreateUserRequest(name,"rag-test-password-only",name,null,null)).id();
        jdbc.update("DELETE FROM sys_user_role WHERE user_id=?",id);
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT ?,id FROM sys_role WHERE code=?",id,role);
        return new PlatformPrincipal(id,name,null,List.of(role),role.equals("EMPLOYEE")?List.of("document:read"):List.of("document:read","document:manage"),"test");
    }
    private DocumentService.Detail document(PlatformPrincipal owner) {
        var d=docs.create(new DocumentService.CreateInput(new DocumentService.Metadata("差旅制度",List.of("EMPLOYEE"),List.of()),new DocumentService.VersionInput("v1",LocalDateTime.now().minusDays(1),null)),new MockMultipartFile("file","test.pdf","application/pdf","%PDF-1.7\ntest".getBytes()),owner);
        long id=d.document().id(),v=d.versions().getFirst().id();
        parse.enqueue(id,v,owner,false); var work=parse.claim();
        parse.complete(work,new ParsingStore.Result(work.taskId(),v,work.contentSha256(),"test",List.of(new ParsingStore.Source(0,1,"第 1 页",null,"第一条 出差前必须提交申请。","TEXT",null,List.of()))));
        return d;
    }
    private void finish(IndexStore.Work work) {
        index.complete(work,json.valueToTree(Map.of("generation",work.generation(),"model",RetrievalClient.MODEL,"keys",work.chunks().stream().map(item -> item.key()).toList())));
    }
    @Test void indexingAndRebuildUseOneEffectiveGenerationAndBlockDrafts() {
        var owner=user("LEGAL"); var employee=user("EMPLOYEE"); var d=document(owner); long id=d.document().id(),v=d.versions().getFirst().id();
        var work=index.prepare(id,v,owner,false); assertThrows(RuntimeException.class,()->index.prepare(id,v,owner,false)); finish(work);
        assertNull(index.prepare(id,v,owner,false)); assertTrue(retrieval.keys(employee).isEmpty());
        docs.publish(id,v,owner); assertEquals(1,retrieval.keys(employee).size());
        var next=index.prepare(id,v,owner,true); assertTrue(retrieval.keys(employee).isEmpty());
        finish(work); assertEquals("PARSED",jdbc.queryForObject("SELECT processing_status FROM document_version WHERE id=?",String.class,v));
        finish(next); assertEquals(List.of(next.chunks().getFirst().key()),retrieval.keys(employee));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM document_chunk WHERE document_version_id=?",Integer.class,v));
    }
    @Test void authoritativeFiltersApplyAgainAfterRanking() {
        var owner=user("LEGAL"); var employee=user("EMPLOYEE"); var d=document(owner); long id=d.document().id(),v=d.versions().getFirst().id();
        var work=index.prepare(id,v,owner,false); finish(work); docs.publish(id,v,owner);
        var scores=new LinkedHashMap<String,Double>(); scores.put(work.chunks().getFirst().key(),0.8);
        assertEquals(1,retrieval.resolve(employee,scores).size());
        // Scope mutation after candidate enumeration must not disclose the old candidate.
        docs.update(id,new DocumentService.Metadata("差旅制度",List.of("LEGAL"),List.of()),owner);
        assertTrue(retrieval.resolve(employee,scores).isEmpty()); assertTrue(retrieval.keys(owner).size()==1);
        docs.update(id,new DocumentService.Metadata("差旅制度",List.of("EMPLOYEE"),List.of()),owner);
        String code="rag_"+UUID.randomUUID();
        jdbc.update("INSERT INTO sys_department(code,name) VALUES(?,?)",code,"验收部门");
        long department=java.util.Objects.requireNonNull(jdbc.queryForObject("SELECT id FROM sys_department WHERE code=?",Long.class,code));
        docs.update(id,new DocumentService.Metadata("差旅制度",List.of("EMPLOYEE"),List.of(department)),owner);
        assertTrue(retrieval.resolve(employee,scores).isEmpty());
        jdbc.update("UPDATE sys_user SET department_id=? WHERE id=?",department,employee.id()); assertEquals(1,retrieval.resolve(employee,scores).size());
        jdbc.update("UPDATE document_version SET effective_at=? WHERE id=?",LocalDateTime.now().plusDays(1),v); assertTrue(retrieval.keys(employee).isEmpty());
        jdbc.update("UPDATE document_version SET effective_at=?,expires_at=? WHERE id=?",LocalDateTime.now().minusDays(2),LocalDateTime.now().minusDays(1),v); assertTrue(retrieval.keys(employee).isEmpty());
        jdbc.update("UPDATE document_version SET expires_at=NULL WHERE id=?",v);
        docs.retire(id,v,owner); assertTrue(retrieval.resolve(employee,scores).isEmpty());
    }
    @Test void expiredAndFailedRunsRejectLateResultsAndAllowRetry() {
        var owner=user("LEGAL"); var d=document(owner); long id=d.document().id(),v=d.versions().getFirst().id();
        var old=index.prepare(id,v,owner,false);
        jdbc.update("UPDATE document_index_run SET lease_until=? WHERE document_version_id=?",LocalDateTime.now().minusDays(1),v);
        assertEquals("INDEX_TIMEOUT",index.state(id,v,owner).errorCode());
        var current=index.prepare(id,v,owner,false); finish(old); index.fail(old,"LATE_FAILURE");
        assertEquals("PROCESSING",index.state(id,v,owner).status());
        index.fail(current,"INDEX_UNAVAILABLE"); assertThrows(RuntimeException.class,()->docs.publish(id,v,owner));
        var retry=index.prepare(id,v,owner,false); finish(retry); assertEquals(3,index.state(id,v,owner).attempts());
        assertThrows(RuntimeException.class,()->index.state(id,v,user("LEGAL")));
    }
    @Test void deletedDocumentsAndInvalidRepliesCannotBecomeReady() {
        var owner=user("LEGAL"); var d=document(owner); long id=d.document().id(),v=d.versions().getFirst().id();
        var work=index.prepare(id,v,owner,false);
        assertThrows(IllegalArgumentException.class,()->index.complete(work,json.valueToTree(Map.of("generation",work.generation(),"model",RetrievalClient.MODEL,"keys",List.of(UUID.randomUUID().toString())))));
        docs.delete(id,owner); finish(work);
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM document_index_run WHERE document_version_id=?",String.class,v));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM document_chunk WHERE document_version_id=?",Integer.class,v));
    }
}
