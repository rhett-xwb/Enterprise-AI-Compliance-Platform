package com.enterprise.compliance.request;

import com.enterprise.compliance.auth.PlatformPrincipal;
import com.enterprise.compliance.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.sql.Statement;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowStore {
    public record Node(@NotBlank @Size(max=100) String name,@Positive long assigneeId) {}
    public record Route(@NotNull @DecimalMin("0") @Digits(integer=16,fraction=2) BigDecimal minimum,
        @DecimalMin("0") @Digits(integer=16,fraction=2) BigDecimal maximum,
        @NotEmpty @Size(max=10) List<@NotNull @Valid Node> nodes) {}
    public record Config(@NotBlank @Size(max=100) String name,@NotEmpty @Size(max=20) List<@NotNull @Valid Route> routes) {}
    public record Decision(@Positive int lockVersion,@Pattern(regexp="APPROVE|REJECT|RETURN") @NotNull String action,
        @NotBlank @Size(max=3000) String opinion) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public WorkflowStore(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    private BusinessException conflict(String message) { return new BusinessException(HttpStatus.CONFLICT,message); }
    private BusinessException missing() { return new BusinessException(HttpStatus.NOT_FOUND,"记录不存在或无访问权限"); }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException(e); } }
    private <T> T decode(String value,Class<T> type) { try { return json.readValue(value,type); } catch(Exception e) { throw new IllegalStateException(e); } }
    private long insert(String sql,Object... values) {
        var key=new GeneratedKeyHolder();
        jdbc.update(c->{var s=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS); for(int i=0;i<values.length;i++) s.setObject(i+1,values[i]); return s;},key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    private boolean eligible(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.id
            JOIN sys_role_permission rp ON rp.role_id=ur.role_id JOIN sys_permission p ON p.id=rp.permission_id
            WHERE u.id=? AND u.enabled=TRUE AND u.deleted_at IS NULL AND p.code='approval:review')
            """,Boolean.class,id));
    }
    public List<Map<String,Object>> assignees() {
        return jdbc.queryForList("""
            SELECT DISTINCT u.id,u.display_name FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.id
            JOIN sys_role_permission rp ON rp.role_id=ur.role_id JOIN sys_permission p ON p.id=rp.permission_id
            WHERE u.enabled=TRUE AND u.deleted_at IS NULL AND p.code='approval:review' ORDER BY u.id
            """);
    }
    @Transactional public Map<String,Object> publish(PlatformPrincipal user,Config config) {
        jdbc.queryForList("SELECT code FROM workflow_publication WHERE code='TRAVEL' FOR UPDATE");
        BigDecimal next=BigDecimal.ZERO;
        for(int i=0;i<config.routes().size();i++) {
            Route r=config.routes().get(i);
            if(!r.minimum().equals(next) && r.minimum().compareTo(next)!=0) throw conflict("金额区间必须从零开始连续，且不能重叠");
            if(r.maximum()==null) { if(i!=config.routes().size()-1) throw conflict("仅最后区间可不设上限"); }
            else if(r.maximum().compareTo(r.minimum())<=0 || i==config.routes().size()-1) throw conflict("上限必须大于下限，最后区间须覆盖所有更高金额");
            for(Node n:r.nodes()) if(!eligible(n.assigneeId())) throw conflict("审批人不存在、已停用或缺少审批权限");
            next=r.maximum();
        }
        int version=jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM workflow_definition WHERE code='TRAVEL'",Integer.class);
        long id=insert("INSERT INTO workflow_definition(code,version_no,name,config_json,published,creator_id) VALUES('TRAVEL',?,?,?,TRUE,?)",version,config.name(),encode(config),user.id());
        jdbc.update("UPDATE workflow_publication SET active_definition_id=? WHERE code='TRAVEL'",id);
        return Map.of("id",id,"version",version);
    }
    public List<Map<String,Object>> definitions() {
        return jdbc.queryForList("SELECT d.id,d.version_no,d.name,d.config_json,d.created_at,(p.active_definition_id=d.id) AS active FROM workflow_definition d JOIN workflow_publication p ON p.code=d.code WHERE d.code='TRAVEL' ORDER BY d.version_no DESC LIMIT 50");
    }
    // Called inside the same request-row transaction as the immutable submission insert.
    @Transactional public void start(long requestId,long applicant,int version,BigDecimal amount,String snapshot) {
        var publication=jdbc.queryForList("SELECT active_definition_id FROM workflow_publication WHERE code='TRAVEL' FOR UPDATE").getFirst();
        if(publication.get("active_definition_id")==null) throw conflict("尚未发布差旅审批流程，请联系管理员配置后重新提交");
        long definition=((Number)publication.get("active_definition_id")).longValue();
        // Use a current read: the request transaction may already have a REPEATABLE READ snapshot.
        Config config=decode(jdbc.queryForObject("SELECT config_json FROM workflow_definition WHERE id=? FOR SHARE",String.class,definition),Config.class);
        Route route=config.routes().stream().filter(r->amount.compareTo(r.minimum())>=0 && (r.maximum()==null || amount.compareTo(r.maximum())<0)).findFirst().orElseThrow(()->conflict("金额未匹配审批流程"));
        for(Node n:route.nodes()) if(n.assigneeId()==applicant || !eligible(n.assigneeId())) throw conflict("审批人不可为申请人，且必须处于启用状态并具备审批权限，请联系管理员");
        long instance=insert("INSERT INTO workflow_instance(request_id,definition_id,request_content_version,request_snapshot,status,route_json) VALUES(?,?,?,?,'IN_REVIEW',?)",requestId,definition,version,snapshot,encode(route));
        task(instance,0,route.nodes().getFirst());
        jdbc.update("UPDATE approval_request SET status='IN_REVIEW',updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",requestId);
        audit(requestId,null,applicant,"START","SUBMITTED","IN_REVIEW","流程版本固定为 #"+definition);
    }
    private void task(long instance,int index,Node node) {
        insert("INSERT INTO approval_task(instance_id,node_key,assignee_id,node_index,lock_version) VALUES(?,?,?,?,1)",instance,node.name(),node.assigneeId(),index);
    }
    private void audit(long request,Long task,long actor,String action,String before,String after,String opinion) {
        jdbc.update("INSERT INTO approval_action(request_id,task_id,actor_id,action,before_status,after_status,opinion) VALUES(?,?,?,?,?,?,?)",request,task,actor,action,before,after,opinion);
    }
    public List<Map<String,Object>> inbox(PlatformPrincipal user,long before,int size,boolean history) {
        if(before<1 || size<1 || size>50) throw new BusinessException(HttpStatus.BAD_REQUEST,"分页参数无效");
        return jdbc.queryForList("SELECT t.*,i.request_id,r.title,r.amount FROM approval_task t JOIN workflow_instance i ON i.id=t.instance_id JOIN approval_request r ON r.id=i.request_id WHERE t.assignee_id=? AND t.id<? AND t.status"+(history?"<>":"=")+"'PENDING' ORDER BY t.id DESC LIMIT ?",user.id(),before,size);
    }
    public Map<String,Object> accessible(PlatformPrincipal user,long id) {
        var rows=jdbc.queryForList("SELECT * FROM approval_request r WHERE r.id=? AND (r.applicant_id=? OR EXISTS(SELECT 1 FROM workflow_instance i JOIN approval_task t ON t.instance_id=i.id WHERE i.request_id=r.id AND t.assignee_id=?))",id,user.id(),user.id());
        if(rows.isEmpty()) throw missing(); return rows.getFirst();
    }
    @Transactional(readOnly=true) public Map<String,Object> history(PlatformPrincipal user,long id) {
        accessible(user,id);
        return Map.of("instances",jdbc.queryForList("SELECT id,definition_id,request_content_version,status,started_at,ended_at,route_json FROM workflow_instance WHERE request_id=? ORDER BY id",id),
            "tasks",jdbc.queryForList("SELECT t.* FROM approval_task t JOIN workflow_instance i ON i.id=t.instance_id WHERE i.request_id=? ORDER BY t.id",id),
            "actions",jdbc.queryForList("SELECT a.*,u.display_name FROM approval_action a JOIN sys_user u ON u.id=a.actor_id WHERE a.request_id=? ORDER BY a.id",id));
    }
    @Transactional(readOnly=true) public Map<String,Object> taskDetail(PlatformPrincipal user,long id) {
        var rows=jdbc.queryForList("SELECT t.*,i.request_id,i.request_snapshot,i.definition_id,i.request_content_version FROM approval_task t JOIN workflow_instance i ON i.id=t.instance_id WHERE t.id=? AND t.assignee_id=?",id,user.id());
        if(rows.isEmpty()) throw missing();
        var t=rows.getFirst();
        // Snapshot includes form/attachment metadata and analysis id, never raw restricted policy text.
        return Map.of("task",t,"history",history(user,((Number)t.get("request_id")).longValue()));
    }
    @Transactional public void decide(PlatformPrincipal user,long id,Decision input) {
        var owned=jdbc.queryForList("SELECT i.request_id FROM approval_task t JOIN workflow_instance i ON i.id=t.instance_id WHERE t.id=? AND t.assignee_id=?",id,user.id());
        if(owned.isEmpty()) throw missing();
        long request=((Number)owned.getFirst().get("request_id")).longValue();
        var r=jdbc.queryForList("SELECT status FROM approval_request WHERE id=? FOR UPDATE",request).getFirst();
        var t=jdbc.queryForList("SELECT * FROM approval_task WHERE id=? FOR UPDATE",id).getFirst();
        if(!r.get("status").equals("IN_REVIEW") || !t.get("status").equals("PENDING") || ((Number)t.get("lock_version")).intValue()!=input.lockVersion()) throw conflict("任务已处理或版本已更新，请刷新");
        if(!eligible(user.id())) throw new BusinessException(HttpStatus.FORBIDDEN,"当前账号无审批权限");
        long instance=((Number)t.get("instance_id")).longValue();
        Route route=decode(jdbc.queryForObject("SELECT route_json FROM workflow_instance WHERE id=?",String.class,instance),Route.class);
        int next=((Number)t.get("node_index")).intValue()+1;
        String status=switch(input.action()) { case "APPROVE" -> next<route.nodes().size()?"IN_REVIEW":"APPROVED"; case "REJECT" -> "REJECTED"; case "RETURN" -> "RETURNED"; default -> throw conflict("不支持的审批动作"); };
        if(status.equals("IN_REVIEW")) {
            Node node=route.nodes().get(next);
            if(!eligible(node.assigneeId())) throw conflict("下一节点审批人已停用或失去权限，请联系管理员恢复后重试");
            task(instance,next,node);
        }
        jdbc.update("UPDATE approval_task SET status=?,lock_version=lock_version+1,completed_at=CURRENT_TIMESTAMP(6) WHERE id=?",input.action(),id);
        jdbc.update("UPDATE approval_request SET status=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",status,request);
        if(!status.equals("IN_REVIEW")) jdbc.update("UPDATE workflow_instance SET status=?,ended_at=CURRENT_TIMESTAMP(6) WHERE id=?",status,instance);
        audit(request,id,user.id(),input.action(),"IN_REVIEW",status,input.opinion());
    }
    @Transactional public void withdraw(PlatformPrincipal user,long id,int version) {
        var rows=jdbc.queryForList("SELECT * FROM approval_request WHERE id=? AND applicant_id=? FOR UPDATE",id,user.id());
        if(rows.isEmpty()) throw missing(); var r=rows.getFirst(); String before=(String)r.get("status");
        if(!Set.of("IN_REVIEW","SUBMITTED").contains(before) || ((Number)r.get("content_version")).intValue()!=version) throw conflict("当前申请不可撤回，请刷新");
        jdbc.update("UPDATE approval_task t JOIN workflow_instance i ON i.id=t.instance_id SET t.status='CANCELLED',t.lock_version=t.lock_version+1,t.completed_at=CURRENT_TIMESTAMP(6) WHERE i.request_id=? AND t.status='PENDING'",id);
        jdbc.update("UPDATE workflow_instance SET status='WITHDRAWN',ended_at=CURRENT_TIMESTAMP(6) WHERE request_id=? AND status='IN_REVIEW'",id);
        jdbc.update("UPDATE approval_request SET status='WITHDRAWN',updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",id);
        audit(id,null,user.id(),"WITHDRAW",before,"WITHDRAWN",null);
    }
    @Transactional public void reopen(PlatformPrincipal user,long id,int version) {
        var rows=jdbc.queryForList("SELECT * FROM approval_request WHERE id=? AND applicant_id=? FOR UPDATE",id,user.id());
        if(rows.isEmpty()) throw missing(); var r=rows.getFirst(); String before=(String)r.get("status");
        if(!Set.of("RETURNED","WITHDRAWN").contains(before) || ((Number)r.get("content_version")).intValue()!=version) throw conflict("仅退回或撤回的当前版本可重新编辑");
        jdbc.update("UPDATE approval_request SET status='DRAFT',content_version=content_version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",id);
        jdbc.update("UPDATE ai_analysis_record SET status='STALE' WHERE request_id=?",id);
        audit(id,null,user.id(),"REOPEN",before,"DRAFT","重新编辑须重新预审并提交");
    }
}
