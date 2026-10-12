package com.enterprise.compliance;

import com.enterprise.compliance.auth.*;
import com.enterprise.compliance.document.*;
import com.enterprise.compliance.user.dto.CreateUserRequest;
import com.enterprise.compliance.user.service.UserService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="app.parser.enabled=false") @AutoConfigureMockMvc @Transactional
@EnabledIfEnvironmentVariable(named="TEST_DB_URL",matches=".+")
class ParsingIntegrationTest extends MySqlTestSupport {
    @Autowired JdbcTemplate jdbc; @Autowired DocumentService docs; @Autowired ParsingStore store;
    @Autowired UserService users; @Autowired AuthService auth; @Autowired MockMvc mvc;
    private PlatformPrincipal user(String role) {
        String name="parse_"+UUID.randomUUID().toString().replace("-","");
        long id=users.create(new CreateUserRequest(name,"parse-test-password-only",name,null,null)).id();
        jdbc.update("DELETE FROM sys_user_role WHERE user_id=?",id);
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT ?,id FROM sys_role WHERE code=?",id,role);
        return new PlatformPrincipal(id,name,null,List.of(role),role.equals("EMPLOYEE")?List.of("document:read"):List.of("document:read","document:manage"),"test");
    }
    private DocumentService.Detail document(PlatformPrincipal owner) {
        var file=new MockMultipartFile("file","test.pdf","application/pdf","%PDF-1.7\ntest".getBytes());
        return docs.create(new DocumentService.CreateInput(new DocumentService.Metadata("解析测试",List.of("EMPLOYEE"),List.of()),
            new DocumentService.VersionInput("v1",LocalDateTime.now().minusDays(1),null)),file,owner);
    }
    private ParsingStore.Result result(ParsingStore.Work work,String text) {
        return new ParsingStore.Result(work.taskId(),work.documentVersionId(),work.contentSha256(),"test-parser",
            List.of(new ParsingStore.Source(0,1,"第 1 页",null,text,"TEXT",null,List.of())));
    }
    @Test void successfulExtractionIsAtomicIdempotentAndNotPublishable() {
        var owner=user("LEGAL"); var d=document(owner); long id=d.document().id(),v=d.versions().getFirst().id();
        var first=store.enqueue(id,v,owner,false);
        assertEquals(first.id(),store.enqueue(id,v,owner,false).id());
        var work=store.claim(); assertNotNull(work); assertNull(store.claim());
        store.complete(work,result(work,"差旅住宿费用需审批"));
        store.complete(work,result(work,"旧响应不覆盖"));
        assertEquals("SUCCEEDED",store.status(id,v,owner).status());
        assertEquals("差旅住宿费用需审批",store.sources(id,v,owner).getFirst().text());
        assertEquals(1,store.sources(id,v,owner).size());
        assertEquals("PARSED",jdbc.queryForObject("SELECT processing_status FROM document_version WHERE id=?",String.class,v));
        assertThrows(com.enterprise.compliance.common.BusinessException.class,()->docs.publish(id,v,owner));
        assertEquals(first.id(),store.enqueue(id,v,owner,true).id());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM document_chunk WHERE document_version_id=?",Integer.class,v));
    }
    @Test void failedRetryAndExpiredLeaseRejectLateResultsWithoutDuplicatingSources() {
        var owner=user("LEGAL"); var d=document(owner); long id=d.document().id(),v=d.versions().getFirst().id();
        store.enqueue(id,v,owner,false); var old=store.claim(); store.fail(old,"PARSE_FAILED");
        assertEquals(0,store.sources(id,v,owner).size());
        store.enqueue(id,v,owner,true); var second=store.claim();
        jdbc.update("UPDATE document_processing_task SET lease_until=? WHERE id=?",LocalDateTime.now().minusDays(1),second.taskId());
        assertNull(store.claim()); assertEquals("WORKER_TIMEOUT",store.status(id,v,owner).errorCode());
        store.enqueue(id,v,owner,true); var current=store.claim();
        store.complete(second,result(second,"不应保存的超时内容"));
        assertTrue(store.sources(id,v,owner).isEmpty());
        store.fail(old,"PARSE_FAILED"); store.complete(current,result(current,"重试成功正文"));
        assertEquals(3,store.status(id,v,owner).attempts());
        assertEquals("重试成功正文",store.sources(id,v,owner).getFirst().text());
    }
    @Test void wrongIdentityEmptyTextAndWrongPageMapAreRejected() {
        var owner=user("LEGAL"); var d=document(owner); long id=d.document().id(),v=d.versions().getFirst().id();
        store.enqueue(id,v,owner,false); var work=store.claim();
        assertThrows(IllegalArgumentException.class,()->store.complete(work,result(work," ")));
        assertThrows(IllegalArgumentException.class,()->store.complete(work,new ParsingStore.Result(work.taskId(),v+1,work.contentSha256(),"test",result(work,"正文").pages())));
        assertThrows(IllegalArgumentException.class,()->store.complete(work,new ParsingStore.Result(work.taskId(),v,work.contentSha256(),"test",List.of(new ParsingStore.Source(0,null,"伪页码",null,"正文","TEXT",null,List.of())))));
        assertTrue(store.sources(id,v,owner).isEmpty());
    }
    @Test void governanceGatesSourcesAndDeletedDocumentsDiscardResults() throws Exception {
        var owner=user("LEGAL"); var other=user("LEGAL"); var employee=user("EMPLOYEE"); var d=document(owner);
        long id=d.document().id(),v=d.versions().getFirst().id();
        String otherToken=auth.login(other.username(),"parse-test-password-only").accessToken();
        String employeeToken=auth.login(employee.username(),"parse-test-password-only").accessToken();
        mvc.perform(get("/api/documents/{id}/versions/{v}/sources",id,v).header("Authorization","Bearer "+otherToken)).andExpect(status().isNotFound());
        mvc.perform(get("/api/documents/{id}/versions/{v}/parse",id,v).header("Authorization","Bearer "+employeeToken)).andExpect(status().isForbidden());
        assertThrows(com.enterprise.compliance.common.BusinessException.class,()->store.enqueue(id,v,other,false));
        store.enqueue(id,v,owner,false); var work=store.claim(); docs.delete(id,owner);
        store.complete(work,result(work,"已删除制度正文"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM document_parse_source WHERE document_version_id=?",Integer.class,v));
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM document_processing_task WHERE id=?",String.class,work.taskId()));
    }
    @Test void retryLimitStopsUnboundedFailuresAndMismatchedVersionsAreDenied() {
        var owner=user("LEGAL"); var d=document(owner); long id=d.document().id(),v=d.versions().getFirst().id();
        assertThrows(com.enterprise.compliance.common.BusinessException.class,()->store.enqueue(id,v+100,owner,false));
        for(int i=0;i<5;i++) { store.enqueue(id,v,owner,true); store.fail(store.claim(),"PARSE_FAILED"); }
        assertThrows(com.enterprise.compliance.common.BusinessException.class,()->store.enqueue(id,v,owner,true));
        assertEquals(5,store.status(id,v,owner).attempts());
    }
}
