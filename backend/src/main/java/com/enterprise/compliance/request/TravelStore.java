package com.enterprise.compliance.request;

import com.enterprise.compliance.auth.PlatformPrincipal;
import com.enterprise.compliance.common.BusinessException;
import com.enterprise.compliance.document.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.sql.Statement;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;

@Service
public class TravelStore {
    public record Form(@Size(max=255) String title,@Size(max=200) String destination,
        LocalDate startDate,LocalDate endDate,@Size(max=3000) String purpose,
        @DecimalMin("0") @Digits(integer=16,fraction=2) BigDecimal amount) {}
    public record Attachment(long id,String filename,String sha256,long size,String contentType) {}
    public record Issue(String field,String code,String message) {}
    public record Review(long id,int contentVersion,String status,String riskLevel,List<Issue> issues,
        String advice,List<RetrievalStore.Hit> evidence,List<Integer> citations,String model,String errorCode,LocalDateTime analyzedAt) {}
    public record Summary(long id,String title,String status,int contentVersion,LocalDateTime updatedAt) {}
    public record Detail(long id,String status,int contentVersion,Form form,List<Attachment> attachments,List<Review> reviews,Object submission) {}
    public record Download(Attachment attachment,byte[] bytes) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final FileStorage files;
    private final UploadValidator uploads;
    private final RetrievalStore retrieval;
    private final BigDecimal maximum;
    private final WorkflowStore workflow;
    public TravelStore(JdbcTemplate jdbc,ObjectMapper json,FileStorage files,UploadValidator uploads,RetrievalStore retrieval,WorkflowStore workflow,
        @Value("${app.travel.maximum-amount:0}") BigDecimal maximum) {
        this.jdbc=jdbc; this.json=json; this.files=files; this.uploads=uploads; this.retrieval=retrieval; this.workflow=workflow; this.maximum=maximum;
        if(maximum.signum()<0) throw new IllegalArgumentException("Travel maximum must be nonnegative");
    }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch(Exception ex) { throw new IllegalStateException(ex); } }
    private <T> T decode(String value,Class<T> type) { try { return json.readValue(value,type); } catch(Exception ex) { throw new IllegalStateException(ex); } }
    private long insert(String sql,Object... values) {
        var key=new GeneratedKeyHolder();
        jdbc.update(c->{var s=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS); for(int i=0;i<values.length;i++) s.setObject(i+1,values[i]); return java.util.Objects.requireNonNull(s);},key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    private Map<String,Object> owned(PlatformPrincipal user,long id,boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM approval_request WHERE id=? AND applicant_id=? AND request_type='TRAVEL'"+(lock?" FOR UPDATE":""),id,user.id());
        if(rows.isEmpty()) throw new BusinessException(HttpStatus.NOT_FOUND,"申请不存在或无访问权限");
        return rows.getFirst();
    }
    private void editable(Map<String,Object> row,int version) {
        if(!row.get("status").equals("DRAFT") || ((Number)row.get("content_version")).intValue()!=version)
            throw new BusinessException(HttpStatus.CONFLICT,"申请已提交或内容已更新，请刷新后操作");
    }
    private List<Attachment> attachments(long id) {
        return jdbc.query("SELECT * FROM approval_attachment WHERE request_id=? AND deleted_at IS NULL ORDER BY id",
            (rs,n)->new Attachment(rs.getLong("id"),rs.getString("original_filename"),rs.getString("content_sha256"),rs.getLong("size_bytes"),rs.getString("content_type")),id);
    }
    @Transactional public Detail create(PlatformPrincipal user,Form form) {
        long id=insert("INSERT INTO approval_request(applicant_id,request_type,title,amount,currency,content_json) VALUES(?,'TRAVEL',?,?,'CNY',?)",user.id(),form.title()==null?"":form.title(),form.amount(),encode(form));
        return detail(user,id);
    }
    @Transactional public Detail update(PlatformPrincipal user,long id,int version,Form form) {
        editable(owned(user,id,true),version);
        jdbc.update("UPDATE approval_request SET title=?,amount=?,content_json=?,content_version=content_version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",form.title()==null?"":form.title(),form.amount(),encode(form),id);
        invalidate(id); return detail(user,id);
    }
    private void invalidate(long id) {
        jdbc.update("UPDATE ai_analysis_record SET status='STALE' WHERE request_id=? AND status<>'STALE'",id);
    }
    private void changed(long id) {
        jdbc.update("UPDATE approval_request SET content_version=content_version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",id); invalidate(id);
    }
    @Transactional public Detail attach(PlatformPrincipal user,long id,int version,MultipartFile file) {
        editable(owned(user,id,true),version);
        if(attachments(id).size()>=10) throw new BusinessException(HttpStatus.BAD_REQUEST,"每笔申请最多 10 个附件");
        var valid=uploads.validate(file); String key=files.store(valid.bytes());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) { if(status!=STATUS_COMMITTED) files.remove(key); }
        });
        insert("INSERT INTO approval_attachment(request_id,content_version,storage_key,original_filename,content_sha256,size_bytes,content_type) VALUES(?,?,?,?,?,?,?)",id,version+1,key,valid.filename(),valid.sha256(),valid.bytes().length,valid.contentType());
        changed(id); return detail(user,id);
    }
    @Transactional public Detail removeAttachment(PlatformPrincipal user,long id,long attachmentId,int version) {
        editable(owned(user,id,true),version);
        if(jdbc.update("UPDATE approval_attachment SET deleted_at=CURRENT_TIMESTAMP(6) WHERE id=? AND request_id=? AND deleted_at IS NULL",attachmentId,id)!=1)
            throw new BusinessException(HttpStatus.NOT_FOUND,"附件不存在");
        changed(id); return detail(user,id);
    }
    @Transactional(readOnly=true) public Download download(PlatformPrincipal user,long id,long attachmentId) {
        owned(user,id,false);
        var attachment=attachments(id).stream().filter(a->a.id()==attachmentId).findFirst().orElseThrow(()->new BusinessException(HttpStatus.NOT_FOUND,"附件不存在"));
        return new Download(attachment,files.read(jdbc.queryForObject("SELECT storage_key FROM approval_attachment WHERE id=? AND request_id=?",String.class,attachmentId,id)));
    }
    public List<Issue> rules(Form f) {
        var issues=new ArrayList<Issue>();
        if(f.title()==null || f.title().isBlank()) issues.add(new Issue("title","REQUIRED","请填写申请标题"));
        if(f.destination()==null || f.destination().isBlank()) issues.add(new Issue("destination","REQUIRED","请填写目的地"));
        if(f.purpose()==null || f.purpose().isBlank()) issues.add(new Issue("purpose","REQUIRED","请填写差旅事由"));
        if(f.startDate()==null || f.endDate()==null) issues.add(new Issue("dates","REQUIRED","请填写起止日期"));
        else if(f.endDate().isBefore(f.startDate())) issues.add(new Issue("dates","DATE_ORDER","结束日期不能早于开始日期"));
        if(f.amount()==null || f.amount().signum()<=0) issues.add(new Issue("amount","POSITIVE_AMOUNT","申请金额必须大于零"));
        else if(maximum.signum()>0 && f.amount().compareTo(maximum)>0) issues.add(new Issue("amount","CONFIGURED_LIMIT","金额超过系统配置上限 "+maximum+" CNY，请联系管理员核对"));
        return List.copyOf(issues);
    }
    @Transactional(readOnly=true) public List<Summary> list(PlatformPrincipal user,long before,int size) {
        if(before<1 || size<1 || size>50) throw new BusinessException(HttpStatus.BAD_REQUEST,"分页参数无效");
        return jdbc.query("SELECT * FROM approval_request WHERE applicant_id=? AND request_type='TRAVEL' AND id<? ORDER BY id DESC LIMIT ?",(rs,n)->new Summary(rs.getLong("id"),rs.getString("title"),rs.getString("status"),rs.getInt("content_version"),rs.getTimestamp("updated_at").toLocalDateTime()),user.id(),before,size);
    }
    @Transactional(readOnly=true) public Detail detail(PlatformPrincipal user,long id) {
        return readDetail(user,id,owned(user,id,false));
    }
    @Transactional(readOnly=true) public Detail reviewerDetail(PlatformPrincipal user,long id) {
        var row=workflow.accessible(user,id);
        var snapshots=jdbc.queryForList("SELECT DISTINCT i.id,i.request_snapshot,i.request_content_version FROM workflow_instance i JOIN approval_task t ON t.instance_id=i.id WHERE i.request_id=? AND t.assignee_id=? ORDER BY i.id DESC LIMIT 20",id,user.id());
        if(snapshots.isEmpty()) throw new BusinessException(HttpStatus.NOT_FOUND,"无分配的审批材料");
        var detail=readDetail(user,id,row);
        var snap=decode((String)snapshots.getFirst().get("request_snapshot"),com.fasterxml.jackson.databind.JsonNode.class);
        var allowed=snapshots.stream().map(s->decode((String)s.get("request_snapshot"),com.fasterxml.jackson.databind.JsonNode.class).get("reviewId").asLong()).collect(java.util.stream.Collectors.toSet());
        var files=new ArrayList<Attachment>(); for(var a:snap.get("attachments")) files.add(json.convertValue(a,Attachment.class));
        return new Detail(id,detail.status(),((Number)snapshots.getFirst().get("request_content_version")).intValue(),json.convertValue(snap.get("form"),Form.class),files,detail.reviews().stream().filter(r->allowed.contains(r.id())).toList(),null);
    }
    @Transactional(readOnly=true) public Download reviewerDownload(PlatformPrincipal user,long id,long attachmentId) {
        workflow.accessible(user,id);
        var snapshots=jdbc.queryForList("SELECT DISTINCT i.request_snapshot FROM workflow_instance i JOIN approval_task t ON t.instance_id=i.id WHERE i.request_id=? AND t.assignee_id=?",String.class,id,user.id());
        boolean allowed=false;
        for(String value:snapshots) for(var a:decode(value,com.fasterxml.jackson.databind.JsonNode.class).get("attachments")) if(a.get("id").asLong()==attachmentId) allowed=true;
        if(!allowed) throw new BusinessException(HttpStatus.NOT_FOUND,"附件不在分配的提交材料中");
        var rows=jdbc.query("SELECT * FROM approval_attachment WHERE id=? AND request_id=?",(rs,n)->new Attachment(rs.getLong("id"),rs.getString("original_filename"),rs.getString("content_sha256"),rs.getLong("size_bytes"),rs.getString("content_type")),attachmentId,id);
        if(rows.isEmpty()) throw new BusinessException(HttpStatus.NOT_FOUND,"附件不存在");
        return new Download(rows.getFirst(),files.read(jdbc.queryForObject("SELECT storage_key FROM approval_attachment WHERE id=?",String.class,attachmentId)));
    }
    private Detail readDetail(PlatformPrincipal user,long id,Map<String,Object> row) {
        int version=((Number)row.get("content_version")).intValue();
        var reviews=jdbc.query("SELECT id,status,result_json FROM ai_analysis_record WHERE request_id=? ORDER BY id DESC LIMIT 20",(rs,n)->{
            var r=decode(rs.getString("result_json"),Review.class);
            boolean hidden=r.evidence().stream().anyMatch(h->retrieval.historicalAccess(user,h).equals("UNAVAILABLE"));
            boolean outdated=r.evidence().stream().anyMatch(h->!retrieval.historicalAccess(user,h).equals("CURRENT"));
            String state=rs.getString("status").equals("STALE") || r.contentVersion()!=version?"STALE":hidden?"RESTRICTED":outdated?"SOURCE_OUTDATED":r.status();
            return new Review(rs.getLong("id"),r.contentVersion(),state,r.riskLevel(),r.issues(),hidden?"制度来源访问权限已变更，请重新预审":r.advice(),hidden?List.of():r.evidence(),hidden?List.of():r.citations(),r.model(),r.errorCode(),r.analyzedAt());
        },id);
        var submissions=jdbc.queryForList("SELECT id,content_version,submitted_at FROM request_submission WHERE request_id=? ORDER BY id DESC LIMIT 1",id);
        return new Detail(id,(String)row.get("status"),version,decode((String)row.get("content_json"),Form.class),attachments(id),reviews,submissions.isEmpty()?null:submissions.getFirst());
    }
    @Transactional public Detail saveReview(PlatformPrincipal user,long id,Review review) {
        var row=owned(user,id,true); editable(row,review.contentVersion());
        if(!review.evidence().isEmpty() && !retrieval.stillCurrent(user,review.evidence()))
            throw new BusinessException(HttpStatus.CONFLICT,"制度来源已变化，请重新预审");
        insert("INSERT INTO ai_analysis_record(request_id,request_content_version,status,risk_level,result_json,model_name) VALUES(?,?,?,?,?,?)",id,review.contentVersion(),review.status(),review.riskLevel(),encode(review),review.model().substring(0,Math.min(100,review.model().length())));
        return detail(user,id);
    }
    @Transactional public Detail submit(PlatformPrincipal user,long id,int version,long reviewId) {
        var row=owned(user,id,true); editable(row,version); var detail=detail(user,id);
        if(!rules(detail.form()).isEmpty()) throw new BusinessException(HttpStatus.CONFLICT,"请先修正必填项、日期或金额问题");
        var review=detail.reviews().isEmpty()?null:detail.reviews().getFirst();
        if(review==null || review.id()!=reviewId || review.contentVersion()!=version || !Set.of("REVIEWED","UNKNOWN","CONFLICT").contains(review.status()))
            throw new BusinessException(HttpStatus.CONFLICT,"请先完成当前内容的预审");
        if(!review.evidence().isEmpty() && !retrieval.stillCurrent(user,review.evidence())) throw new BusinessException(HttpStatus.CONFLICT,"制度依据已变化，请重新预审");
        insert("INSERT INTO request_submission(request_id,content_version,snapshot_json,analysis_id) VALUES(?,?,?,?)",id,version,encode(Map.of("form",detail.form(),"currency","CNY","attachments",detail.attachments(),"reviewId",reviewId)),reviewId);
        jdbc.update("UPDATE approval_request SET status='SUBMITTED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",id);
        jdbc.update("INSERT INTO approval_action(request_id,actor_id,action,before_status,after_status) VALUES(?,?,'SUBMIT','DRAFT','SUBMITTED')",id,user.id());
        workflow.start(id,user.id(),version,detail.form().amount(),encode(Map.of("form",detail.form(),"currency","CNY","attachments",detail.attachments(),"reviewId",reviewId)));
        return detail(user,id);
    }
}
