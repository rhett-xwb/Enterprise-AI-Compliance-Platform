package com.enterprise.compliance.async;

import com.enterprise.compliance.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** This table is both the transactional outbox and the bounded job ledger. */
@Service
public class AsyncJobs {
    public record Message(long id,String token) {}
    public record Work(long id,long documentId,long versionId,String type,String payload,String token,int attempts) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public AsyncJobs(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    private LocalDateTime now() { return LocalDateTime.now(ZoneId.of("Asia/Shanghai")); }
    private String token() { return UUID.randomUUID().toString(); }
    @Transactional
    public void enqueue(long doc,long version,String type,String key,Object payload) {
        try {
            jdbc.update("INSERT INTO document_async_job(document_id,document_version_id,task_type,task_key,payload_json,message_token) VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE status=IF(status='FAILED','PENDING',status),message_token=IF(status='PENDING',VALUES(message_token),message_token),dispatched_at=IF(status='PENDING',NULL,dispatched_at),next_attempt_at=IF(status='PENDING',VALUES(next_attempt_at),next_attempt_at),updated_at=VALUES(updated_at)",doc,version,type,key,json.writeValueAsString(payload),token());
        } catch(com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalArgumentException("Invalid job payload"); }
    }
    public boolean active(long version,String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM document_async_job WHERE document_version_id=? AND task_type=? AND status IN ('PENDING','PROCESSING')",Integer.class,version,type)>0;
    }
    public Long failedIndex(long version) {
        var ids=jdbc.queryForList("SELECT j.id FROM document_async_job j JOIN document_index_run r ON r.document_version_id=j.document_version_id AND r.generation=JSON_UNQUOTE(JSON_EXTRACT(j.payload_json,'$.generation')) WHERE j.document_version_id=? AND j.task_type='INDEX' AND j.status='FAILED' ORDER BY j.id DESC LIMIT 1",Long.class,version);
        return ids.isEmpty()?null:ids.getFirst();
    }
    @Transactional
    public List<Message> dispatchable() {
        // Existing pending parse tasks are adopted when MQ is enabled after an upgrade.
        jdbc.update("INSERT IGNORE INTO document_async_job(document_id,document_version_id,task_type,task_key,payload_json,message_token) SELECT v.document_id,t.document_version_id,'PARSE',CONCAT('parse:',t.document_version_id),JSON_OBJECT('taskId',t.id),UUID() FROM document_processing_task t JOIN document_version v ON v.id=t.document_version_id WHERE t.task_type='PARSE' AND (t.status='PENDING' OR (t.status='PROCESSING' AND t.lease_until<?))",now());
        return jdbc.query("SELECT id,message_token FROM document_async_job WHERE status='PENDING' AND next_attempt_at<=? AND (dispatched_at IS NULL OR dispatched_at<?) ORDER BY id LIMIT 20",(r,n)->new Message(r.getLong(1),r.getString(2)),now(),now().minusSeconds(60));
    }
    @Transactional
    public void dispatched(Message m) {
        jdbc.update("UPDATE document_async_job SET dispatched_at=?,error_code=NULL WHERE id=? AND message_token=? AND status='PENDING'",now(),m.id(),m.token());
    }
    @Transactional
    public void deliveryFailed(Message m) {
        jdbc.update("UPDATE document_async_job SET error_code='BROKER_UNAVAILABLE',next_attempt_at=? WHERE id=? AND message_token=? AND status='PENDING'",now().plusSeconds(10),m.id(),m.token());
    }
    @Transactional
    public Work claim(Message message) {
        var ids=jdbc.queryForList("SELECT document_id FROM document_async_job WHERE id=?",Long.class,message.id());
        if(ids.isEmpty()) return null;
        var doc=jdbc.queryForMap("SELECT deleted_at FROM document WHERE id=? FOR UPDATE",ids.getFirst());
        var job=jdbc.queryForMap("SELECT * FROM document_async_job WHERE id=? FOR UPDATE",message.id());
        if(!"PENDING".equals(job.get("status")) || !message.token().equals(job.get("message_token")) || jdbc.queryForObject("SELECT next_attempt_at>? FROM document_async_job WHERE id=?",Boolean.class,now(),message.id())) return null;
        long version=((Number)job.get("document_version_id")).longValue();
        String type=(String)job.get("task_type");
        if(type.equals("INDEX") && !indexCurrent(message.id(),version)) {
            jdbc.update("UPDATE document_async_job SET status='FAILED',error_code='GENERATION_SUPERSEDED',updated_at=? WHERE id=?",now(),message.id()); return null;
        }
        if(doc.get("deleted_at")!=null || "RETIRED".equals(jdbc.queryForObject("SELECT publication_status FROM document_version WHERE id=?",String.class,version))) {
            terminal(message.id(),version,type,"DOCUMENT_INACTIVE"); return null;
        }
        if(succeeded(version,type)) {
            jdbc.update("UPDATE document_async_job SET status='SUCCEEDED',error_code=NULL,updated_at=? WHERE id=?",now(),message.id()); return null;
        }
        int attempts=((Number)job.get("attempts")).intValue();
        if(type.equals("PARSE")) attempts=Math.max(attempts,jdbc.queryForObject("SELECT attempts FROM document_processing_task WHERE document_version_id=? AND task_type='PARSE'",Integer.class,version));
        if(attempts>=5) jdbc.update("UPDATE document_async_job SET attempts=5 WHERE id=?",message.id());
        if(attempts>=5) { terminal(message.id(),version,type,"ATTEMPT_LIMIT"); return null; }
        String run=token();
        // One worker owns the token. The lease exceeds the bounded parser/index HTTP calls.
        jdbc.update("UPDATE document_async_job SET status='PROCESSING',attempts=?,run_token=?,lease_until=?,error_code=NULL,updated_at=? WHERE id=?",attempts+1,run,now().plusSeconds(600),now(),message.id());
        if(type.equals("PARSE")) {
            jdbc.update("UPDATE document_processing_task SET status='PENDING',run_token=NULL,lease_until=NULL WHERE document_version_id=? AND task_type='PARSE' AND status IN ('FAILED','PROCESSING')",version);
        } else {
            jdbc.update("UPDATE document_index_run SET status='PROCESSING',error_code=NULL,lease_until=?,attempts=? WHERE document_version_id=?",now().plusSeconds(600),attempts+1,version);
        }
        return new Work(message.id(),ids.getFirst(),version,type,(String)job.get("payload_json"),run,attempts+1);
    }
    private boolean succeeded(long version,String type) {
        String sql=type.equals("PARSE") ? "SELECT COUNT(*) FROM document_processing_task WHERE document_version_id=? AND task_type='PARSE' AND status='SUCCEEDED'" : "SELECT COUNT(*) FROM document_index_run WHERE document_version_id=? AND status='SUCCEEDED'";
        return jdbc.queryForObject(sql,Integer.class,version)>0;
    }
    private boolean indexCurrent(long id,long version) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM document_index_run r JOIN document_async_job j ON j.id=? WHERE r.document_version_id=? AND r.generation=JSON_UNQUOTE(JSON_EXTRACT(j.payload_json,'$.generation'))",Integer.class,id,version)>0;
    }
    @Transactional
    public void finished(Work work,String error) {
        jdbc.queryForList("SELECT id FROM document WHERE id=? FOR UPDATE",work.documentId());
        var job=jdbc.queryForMap("SELECT * FROM document_async_job WHERE id=? FOR UPDATE",work.id());
        if(!"PROCESSING".equals(job.get("status")) || !work.token().equals(job.get("run_token"))) return;
        if(work.type().equals("INDEX") && !indexCurrent(work.id(),work.versionId())) { terminal(work.id(),work.versionId(),work.type(),"GENERATION_SUPERSEDED"); return; }
        if(succeeded(work.versionId(),work.type())) {
            jdbc.update("UPDATE document_async_job SET status='SUCCEEDED',error_code=NULL,run_token=NULL,lease_until=NULL,updated_at=? WHERE id=?",now(),work.id()); return;
        }
        String code=error==null?"RESULT_NOT_COMMITTED":error;
        boolean transientFailure=Set.of("PARSER_UNAVAILABLE","PARSER_BUSY","PARSE_TIMEOUT","INDEX_UNAVAILABLE","WORKER_TIMEOUT","RESULT_NOT_COMMITTED").contains(code);
        boolean retry=transientFailure && work.attempts()<3;
        if(!retry) { terminal(work.id(),work.versionId(),work.type(),code); return; }
        jdbc.update("UPDATE document_async_job SET status='PENDING',error_code=?,message_token=?,run_token=NULL,lease_until=NULL,dispatched_at=NULL,next_attempt_at=?,updated_at=? WHERE id=?",code,token(),now().plusSeconds(5L*work.attempts()),now(),work.id());
        if(work.type().equals("PARSE")) {
            jdbc.update("UPDATE document_processing_task SET status='PENDING',error_code=?,run_token=NULL,lease_until=NULL WHERE document_version_id=? AND task_type='PARSE'",code,work.versionId());
            jdbc.update("UPDATE document_version SET processing_status='PENDING' WHERE id=?",work.versionId());
        } else jdbc.update("UPDATE document_index_run SET status='PROCESSING',error_code=?,lease_until=? WHERE document_version_id=?",code,now().plusSeconds(600),work.versionId());
    }
    private void terminal(long id,long version,String type,String code) {
        jdbc.update("UPDATE document_async_job SET status='FAILED',error_code=?,run_token=NULL,lease_until=NULL,updated_at=? WHERE id=?",code,now(),id);
        if(type.equals("PARSE")) {
            jdbc.update("UPDATE document_processing_task SET status='FAILED',error_code=?,run_token=NULL,lease_until=NULL WHERE document_version_id=? AND task_type='PARSE' AND status<>'SUCCEEDED'",code,version);
            jdbc.update("UPDATE document_version SET processing_status='FAILED' WHERE id=? AND processing_status IN ('PENDING','PROCESSING')",version);
        } else jdbc.update("UPDATE document_index_run SET status='FAILED',error_code=?,lease_until=NULL WHERE document_version_id=? AND status<>'SUCCEEDED' AND generation=(SELECT JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.generation')) FROM document_async_job WHERE id=?)",code,version,id);
    }
    @Transactional
    public void recover() {
        var rows=jdbc.query("SELECT * FROM document_async_job WHERE status='PROCESSING' AND lease_until<? LIMIT 20",(r,n)->new Work(r.getLong("id"),r.getLong("document_id"),r.getLong("document_version_id"),r.getString("task_type"),r.getString("payload_json"),r.getString("run_token"),r.getInt("attempts")),now());
        for(var work:rows) finished(work,"WORKER_TIMEOUT");
    }
    public List<Map<String,Object>> list() {
        return jdbc.queryForList("SELECT id,document_id,document_version_id,task_type,status,attempts,error_code,next_attempt_at,dispatched_at,lease_until,created_at,updated_at FROM document_async_job ORDER BY id DESC LIMIT 100");
    }
    public List<Map<String,Object>> metrics() { return jdbc.queryForList("SELECT task_type,status,COUNT(*) AS count,MIN(created_at) AS oldest_created_at FROM document_async_job GROUP BY task_type,status"); }
    @Transactional
    public void retry(long id) {
        var ids=jdbc.queryForList("SELECT document_id FROM document_async_job WHERE id=?",Long.class,id);
        if(ids.isEmpty()) throw new BusinessException(HttpStatus.NOT_FOUND,"任务不存在");
        var doc=jdbc.queryForMap("SELECT deleted_at FROM document WHERE id=? FOR UPDATE",ids.getFirst());
        var job=jdbc.queryForMap("SELECT * FROM document_async_job WHERE id=? FOR UPDATE",id);
        if(doc.get("deleted_at")!=null || !"FAILED".equals(job.get("status")) || ((Number)job.get("attempts")).intValue()>=5) throw new BusinessException(HttpStatus.CONFLICT,"任务不可重试或已达到五次尝试上限");
        long version=((Number)job.get("document_version_id")).longValue();
        if("INDEX".equals(job.get("task_type")) && !indexCurrent(id,version)) throw new BusinessException(HttpStatus.CONFLICT,"索引任务已被新代次替代");
        if("RETIRED".equals(jdbc.queryForObject("SELECT publication_status FROM document_version WHERE id=?",String.class,version))) throw new BusinessException(HttpStatus.CONFLICT,"停用版本不可重试");
        jdbc.update("UPDATE document_async_job SET status='PENDING',message_token=?,dispatched_at=NULL,next_attempt_at=?,updated_at=? WHERE id=?",token(),now(),now(),id);
        if("PARSE".equals(job.get("task_type"))) {
            jdbc.update("UPDATE document_processing_task SET status='PENDING',error_code=NULL WHERE document_version_id=? AND task_type='PARSE'",version);
            jdbc.update("UPDATE document_version SET processing_status='PENDING' WHERE id=?",version);
        } else jdbc.update("UPDATE document_index_run SET status='PROCESSING',error_code=NULL,lease_until=? WHERE document_version_id=?",now().plusSeconds(600),version);
    }
}
