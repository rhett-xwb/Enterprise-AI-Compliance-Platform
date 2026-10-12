package com.enterprise.compliance;

import com.enterprise.compliance.auth.PlatformPrincipal;
import com.enterprise.compliance.common.BusinessException;
import com.enterprise.compliance.request.*;
import com.enterprise.compliance.user.dto.CreateUserRequest;
import com.enterprise.compliance.user.service.UserService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.parser.enabled=false","app.retrieval.enabled=false","app.qa.enabled=false"})
@AutoConfigureMockMvc @Transactional
@EnabledIfEnvironmentVariable(named="TEST_DB_URL",matches=".+")
class WorkflowIntegrationTest extends MySqlTestSupport {
    @Autowired WorkflowStore workflow; @Autowired TravelStore travel; @Autowired TravelReview review;
    @Autowired UserService users; @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    private final List<Long> fixtureUsers=new ArrayList<>(); private Long previousDefinition;
    @BeforeEach void rememberPublication() { previousDefinition=jdbc.queryForObject("SELECT active_definition_id FROM workflow_publication WHERE code='TRAVEL'",Long.class); }
    @AfterEach void cleanupCommittedFixtures() {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) return;
        for(long user:fixtureUsers) {
            for(long id:jdbc.queryForList("SELECT id FROM approval_request WHERE applicant_id=?",Long.class,user)) {
                jdbc.update("DELETE FROM approval_action WHERE request_id=?",id);
                jdbc.update("DELETE t FROM approval_task t JOIN workflow_instance i ON i.id=t.instance_id WHERE i.request_id=?",id);
                jdbc.update("DELETE FROM workflow_instance WHERE request_id=?",id);
                jdbc.update("DELETE FROM request_submission WHERE request_id=?",id);
                jdbc.update("DELETE FROM ai_analysis_record WHERE request_id=?",id);
                jdbc.update("DELETE FROM approval_attachment WHERE request_id=?",id);
                jdbc.update("DELETE FROM approval_request WHERE id=?",id);
            }
        }
        jdbc.update("UPDATE workflow_publication SET active_definition_id=? WHERE code='TRAVEL'",previousDefinition);
        for(long user:fixtureUsers) { jdbc.update("DELETE FROM workflow_definition WHERE creator_id=?",user); jdbc.update("DELETE FROM sys_user_role WHERE user_id=?",user); jdbc.update("DELETE FROM sys_user WHERE id=?",user); }
    }
    private PlatformPrincipal user(boolean approver) {
        String name="wf_"+UUID.randomUUID().toString().replace("-","");
        long id=users.create(new CreateUserRequest(name,"workflow-test-only-password",name,null,null)).id();
        fixtureUsers.add(id);
        if(approver) jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT ?,id FROM sys_role WHERE code='LEGAL'",id);
        return new PlatformPrincipal(id,name,null,List.of(approver?"LEGAL":"EMPLOYEE"),approver?List.of("approval:review","approval:submit"):List.of("approval:submit"),"test");
    }
    private WorkflowStore.Node node(PlatformPrincipal u,String name) { return new WorkflowStore.Node(name,u.id()); }
    private WorkflowStore.Config config(PlatformPrincipal a,PlatformPrincipal b) {
        return new WorkflowStore.Config("差旅",List.of(new WorkflowStore.Route(BigDecimal.ZERO,new BigDecimal("1000"),List.of(node(a,"主管"))),new WorkflowStore.Route(new BigDecimal("1000"),null,List.of(node(a,"主管"),node(b,"财务")))));
    }
    private TravelStore.Detail draft(PlatformPrincipal u,String amount) { return travel.create(u,new TravelStore.Form("差旅","上海",LocalDate.of(2026,11,1),LocalDate.of(2026,11,2),"项目沟通",new BigDecimal(amount))); }
    private TravelStore.Detail submit(PlatformPrincipal u,long id,int version) { var r=review.review(u,id,version).reviews().getFirst(); return travel.submit(u,id,version,r.id()); }
    private long task(PlatformPrincipal u,long request) { return ((Number)workflow.inbox(u,Long.MAX_VALUE,50,false).stream().filter(t->((Number)t.get("request_id")).longValue()==request).findFirst().orElseThrow().get("id")).longValue(); }
    private void decide(PlatformPrincipal u,long request,String action) { workflow.decide(u,task(u,request),new WorkflowStore.Decision(1,action,"已核对材料")); }
    @Test void configurableAmountBoundaryAndSequentialApproval() {
        var a=user(true); var b=user(true); var u=user(false); workflow.publish(a,config(a,b));
        var low=draft(u,"999.99"); submit(u,low.id(),1); decide(a,low.id(),"APPROVE"); assertEquals("APPROVED",travel.detail(u,low.id()).status());
        var high=draft(u,"1000"); submit(u,high.id(),1); decide(a,high.id(),"APPROVE"); assertEquals("IN_REVIEW",travel.detail(u,high.id()).status()); decide(b,high.id(),"APPROVE"); assertEquals("APPROVED",travel.detail(u,high.id()).status());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM approval_task t JOIN workflow_instance i ON i.id=t.instance_id WHERE i.request_id=?",Integer.class,high.id()));
    }
    @Test void newPublicationDoesNotChangeRunningRoute() {
        var a=user(true); var b=user(true); var u=user(false); var v1=workflow.publish(a,config(a,b));
        var d=draft(u,"2000"); submit(u,d.id(),1);
        workflow.publish(a,new WorkflowStore.Config("新流程",List.of(new WorkflowStore.Route(BigDecimal.ZERO,null,List.of(node(b,"新节点"))))));
        decide(a,d.id(),"APPROVE"); assertEquals("财务",workflow.inbox(b,Long.MAX_VALUE,50,false).getFirst().get("node_key"));
        assertEquals(v1.get("id"),jdbc.queryForObject("SELECT definition_id FROM workflow_instance WHERE request_id=?",Long.class,d.id()));
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void missingConfigurationInvalidAssigneeSelfApprovalAndGapRollBack() {
        var a=user(true); var b=user(true); var u=user(false); jdbc.update("UPDATE workflow_publication SET active_definition_id=NULL WHERE code='TRAVEL'");
        var d=draft(u,"100"); var r=review.review(u,d.id(),1).reviews().getFirst();
        assertThrows(BusinessException.class,()->travel.submit(u,d.id(),1,r.id())); assertEquals("DRAFT",travel.detail(u,d.id()).status());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM request_submission WHERE request_id=?",Integer.class,d.id()));
        assertThrows(BusinessException.class,()->workflow.publish(a,new WorkflowStore.Config("缺口",List.of(new WorkflowStore.Route(BigDecimal.ONE,null,List.of(node(a,"主管")))))));
        assertThrows(BusinessException.class,()->workflow.publish(a,new WorkflowStore.Config("员工",List.of(new WorkflowStore.Route(BigDecimal.ZERO,null,List.of(node(u,"无权限")))))));
        workflow.publish(a,config(a,b)); jdbc.update("UPDATE sys_user SET enabled=FALSE WHERE id=?",b.id());
        var high=draft(u,"1000"); assertThrows(BusinessException.class,()->submit(u,high.id(),1));
        var self=draft(a,"100"); assertThrows(BusinessException.class,()->submit(a,self.id(),1));
    }
    @Test void rejectReturnWithdrawReopenRetainAllSnapshots() {
        var a=user(true); var b=user(true); var u=user(false); workflow.publish(a,config(a,b));
        var rejected=draft(u,"100"); submit(u,rejected.id(),1); decide(a,rejected.id(),"REJECT"); assertEquals("REJECTED",travel.detail(u,rejected.id()).status()); assertThrows(BusinessException.class,()->workflow.reopen(u,rejected.id(),1));
        var d=draft(u,"100"); submit(u,d.id(),1); decide(a,d.id(),"RETURN"); assertEquals("RETURNED",travel.detail(u,d.id()).status());
        workflow.reopen(u,d.id(),1); assertEquals(2,travel.detail(u,d.id()).contentVersion()); assertEquals("STALE",travel.detail(u,d.id()).reviews().getFirst().status());
        submit(u,d.id(),2); workflow.withdraw(u,d.id(),2); assertEquals("WITHDRAWN",travel.detail(u,d.id()).status()); assertTrue(workflow.inbox(a,Long.MAX_VALUE,50,false).isEmpty());
        workflow.reopen(u,d.id(),2); submit(u,d.id(),3); decide(a,d.id(),"APPROVE");
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM request_submission WHERE request_id=?",Integer.class,d.id()));
        assertEquals(3,((List<?>)workflow.history(u,d.id()).get("instances")).size());
    }
    @Test void ownershipStaleVersionAndHttpAuthorization() throws Exception {
        var a=user(true); var b=user(true); var u=user(false); workflow.publish(a,config(a,b)); var d=draft(u,"100"); submit(u,d.id(),1); long t=task(a,d.id());
        assertThrows(BusinessException.class,()->workflow.decide(b,t,new WorkflowStore.Decision(1,"APPROVE","越权")));
        assertThrows(BusinessException.class,()->workflow.history(user(false),d.id()));
        assertThrows(BusinessException.class,()->workflow.decide(a,t,new WorkflowStore.Decision(2,"APPROVE","过期")));
        var auth=authentication(new UsernamePasswordAuthenticationToken(a,null,List.of(new SimpleGrantedAuthority("approval:review"))));
        mvc.perform(get("/api/workflows/tasks")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/workflows/tasks/"+t+"/decisions").with(auth).contentType("application/json").content("{\"lockVersion\":1,\"action\":\"APPROVE\",\"opinion\":\"\"}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/workflows/definitions").with(auth)).andExpect(status().isForbidden());
        decide(a,d.id(),"APPROVE"); assertThrows(BusinessException.class,()->workflow.decide(a,t,new WorkflowStore.Decision(1,"REJECT","重复")));
    }
    @Test void reviewerSeesAssignedSnapshotInsteadOfLaterDraft() {
        var a=user(true); var b=user(true); var u=user(false); workflow.publish(a,config(a,b)); var d=draft(u,"100"); submit(u,d.id(),1); decide(a,d.id(),"RETURN"); workflow.reopen(u,d.id(),1);
        travel.update(u,d.id(),2,new TravelStore.Form("后来草稿","北京",LocalDate.of(2026,11,1),LocalDate.of(2026,11,2),"尚未提交",new BigDecimal("999")));
        var old=travel.reviewerDetail(a,d.id()); assertEquals("差旅",old.form().title()); assertEquals(1,old.contentVersion()); assertThrows(BusinessException.class,()->travel.reviewerDetail(b,d.id()));
    }
    @Test void lostNextNodePermissionStopsProgressWithoutClosingCurrentTask() {
        var a=user(true); var b=user(true); var u=user(false); workflow.publish(a,config(a,b)); var d=draft(u,"1000"); submit(u,d.id(),1);
        jdbc.update("DELETE FROM sys_user_role WHERE user_id=?",b.id()); assertThrows(BusinessException.class,()->decide(a,d.id(),"APPROVE")); assertEquals("IN_REVIEW",travel.detail(u,d.id()).status()); assertTrue(task(a,d.id())>0);
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void submissionSeesPublicationCommittedAfterItsRepeatableReadSnapshot() {
        var a=user(true); var b=user(true); var u=user(false); workflow.publish(a,config(a,b));
        var d=draft(u,"100"); var r=review.review(u,d.id(),1).reviews().getFirst();
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status->{
            jdbc.queryForObject("SELECT COUNT(*) FROM workflow_definition",Integer.class);
            var published=CompletableFuture.supplyAsync(()->workflow.publish(a,new WorkflowStore.Config("并发新版本",List.of(new WorkflowStore.Route(BigDecimal.ZERO,null,List.of(node(b,"新审批人"))))))).orTimeout(10,TimeUnit.SECONDS).join();
            travel.submit(u,d.id(),1,r.id());
            assertEquals(published.get("id"),jdbc.queryForObject("SELECT definition_id FROM workflow_instance WHERE request_id=? FOR SHARE",Long.class,d.id()));
        });
        assertTrue(task(b,d.id())>0);
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void concurrentDecisionsAndWithdrawalHaveExactlyOneWinner() throws Exception {
        var a=user(true); var b=user(true); var u=user(false); workflow.publish(a,config(a,b));
        for(boolean withdrawal:List.of(false,true)) {
            var d=draft(u,"100"); submit(u,d.id(),1); long t=task(a,d.id()); var gate=new CountDownLatch(1);
            try(var pool=Executors.newFixedThreadPool(2)) {
                var first=pool.submit(()->{gate.await(); try {workflow.decide(a,t,new WorkflowStore.Decision(1,"APPROVE","同意")); return true;} catch(BusinessException e) {return false;}});
                var second=pool.submit(()->{gate.await(); try {if(withdrawal) workflow.withdraw(u,d.id(),1); else workflow.decide(a,t,new WorkflowStore.Decision(1,"REJECT","拒绝")); return true;} catch(BusinessException e) {return false;}});
                gate.countDown(); assertNotEquals(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
            }
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM approval_action WHERE request_id=? AND action IN ('APPROVE','REJECT','WITHDRAW')",Integer.class,d.id()));
            assertFalse(Set.of("DRAFT","IN_REVIEW").contains(travel.detail(u,d.id()).status()));
        }
    }
}
