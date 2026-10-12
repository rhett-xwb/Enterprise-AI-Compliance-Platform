package com.enterprise.compliance.document;

import com.enterprise.compliance.auth.PlatformPrincipal;
import com.enterprise.compliance.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ParsingStore {
    public record Task(long id, long documentVersionId, String status, int attempts, String errorCode, String parserVersion) {}
    public record Source(int sourceIndex, Integer pageNumber, String location, String sectionTitle, String text,
                         String method, Double confidence, List<String> warnings) {}
    public record Result(long taskId, long documentVersionId, String contentSha256, String parserVersion, List<Source> pages) {}
    public record Work(long taskId, long documentVersionId, long documentId, String token, String storageKey,
                       String contentSha256, String contentType, long sizeBytes) {}
    private final JdbcTemplate jdbc;
    private final DocumentService docs;
    private final ObjectMapper json;
    private final int timeout;
    private final com.enterprise.compliance.async.SourceCache cache;
    public ParsingStore(JdbcTemplate jdbc, DocumentService docs, ObjectMapper json,com.enterprise.compliance.async.SourceCache cache,
                        @Value("${app.parser.timeout-seconds:190}") int timeout) {
        this.jdbc=jdbc; this.docs=docs; this.json=json; this.timeout=timeout;
        this.cache=cache;
    }
    private LocalDateTime now() { return LocalDateTime.now(ZoneId.of("Asia/Shanghai")); }
    private BusinessException conflict(String message) { return new BusinessException(HttpStatus.CONFLICT,message); }
    private void check(long id,long version,PlatformPrincipal user) {
        docs.requireManaged(id,user);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM document_version WHERE id=? AND document_id=?",Integer.class,version,id)==0)
            throw new BusinessException(HttpStatus.NOT_FOUND,"版本不存在或无访问权限");
    }
    private Task task(long version) {
        var rows=jdbc.query("SELECT * FROM document_processing_task WHERE document_version_id=? AND task_type='PARSE'",
            (rs,n)->new Task(rs.getLong("id"),version,rs.getString("status"),rs.getInt("attempts"),rs.getString("error_code"),rs.getString("parser_version")),version);
        return rows.isEmpty()?null:rows.getFirst();
    }
    @Transactional
    public Task enqueue(long id,long version,PlatformPrincipal user,boolean retry) {
        check(id,version,user);
        var existing=task(version);
        if(existing!=null && !existing.status().equals("FAILED")) return existing;
        if(existing!=null && !retry) return existing;
        if(!"DRAFT".equals(jdbc.queryForObject("SELECT publication_status FROM document_version WHERE id=?",String.class,version)))
            throw conflict("只有草稿版本可以解析");
        if(existing!=null && existing.attempts()>=5) throw conflict("已达到五次解析尝试上限，请上传修正版");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM document_processing_task WHERE task_type='PARSE' AND status IN ('PENDING','PROCESSING')",Integer.class)>=100)
            throw conflict("解析队列已满，请稍后重试");
        if(existing==null) jdbc.update("INSERT INTO document_processing_task(document_version_id,task_key,task_type) VALUES(?,?,'PARSE')",version,"parse:"+version);
        else jdbc.update("UPDATE document_processing_task SET status='PENDING',error_code=NULL,run_token=NULL,lease_until=NULL,updated_at=? WHERE id=?",now(),existing.id());
        jdbc.update("UPDATE document_version SET processing_status='PENDING' WHERE id=?",version);
        jdbc.update("INSERT INTO document_audit(document_id,version_id,actor_id,action,detail_json) VALUES(?,?,?,?,'{}')",id,version,user.id(),existing==null?"PARSE_REQUEST":"PARSE_RETRY");
        return task(version);
    }
    @Transactional
    public Task status(long id,long version,PlatformPrincipal user) { check(id,version,user); return task(version); }
    @Transactional
    public List<Source> sources(long id,long version,PlatformPrincipal user) {
        check(id,version,user);
        var task=task(version);
        boolean immutable=task!=null && "SUCCEEDED".equals(task.status());
        String hash=immutable ? jdbc.queryForObject("SELECT content_sha256 FROM document_version WHERE id=?",String.class,version) : "";
        if(immutable) { var cached=cache.get(version,hash); if(cached!=null) return cached; }
        var result=jdbc.query("SELECT * FROM document_parse_source WHERE document_version_id=? ORDER BY source_index",(rs,n)->{
            try {
                return new Source(rs.getInt("source_index"),rs.getObject("page_number",Integer.class),rs.getString("location"),rs.getString("section_title"),rs.getString("text_content"),rs.getString("extraction_method"),rs.getObject("confidence",Double.class),
                    json.readValue(rs.getString("warnings_json"),json.getTypeFactory().constructCollectionType(List.class,String.class)));
            } catch(Exception ex) { throw new IllegalStateException("Invalid persisted parse source"); }
        },version);
        if(immutable && org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void afterCommit() { cache.put(version,hash,result); }
            });
        return result;
    }
    @Transactional
    public Work claim() {
        return claim(null);
    }
    @Transactional
    public Work claim(Long requestedTaskId) {
        // Recover crashed workers. The run token makes any late response harmless.
        var expired=jdbc.queryForList("SELECT id,document_version_id FROM document_processing_task WHERE task_type='PARSE' AND status='PROCESSING' AND lease_until<?",now());
        for(var row:expired) {
            long version=((Number)row.get("document_version_id")).longValue();
            long doc=jdbc.queryForObject("SELECT document_id FROM document_version WHERE id=?",Long.class,version);
            jdbc.queryForList("SELECT id FROM document WHERE id=? FOR UPDATE",doc);
            if(jdbc.update("UPDATE document_processing_task SET status='FAILED',error_code='WORKER_TIMEOUT',run_token=NULL,lease_until=NULL,updated_at=? WHERE id=? AND status='PROCESSING' AND lease_until<?",now(),row.get("id"),now())==1)
                jdbc.update("UPDATE document_version SET processing_status='FAILED' WHERE id=?",version);
        }
        var ids=requestedTaskId==null
            ? jdbc.queryForList("SELECT document_version_id FROM document_processing_task WHERE task_type='PARSE' AND status='PENDING' ORDER BY id LIMIT 1",Long.class)
            : jdbc.queryForList("SELECT document_version_id FROM document_processing_task WHERE task_type='PARSE' AND status='PENDING' AND id=?",Long.class,requestedTaskId);
        if(ids.isEmpty()) return null;
        long version=ids.getFirst();
        long doc=jdbc.queryForObject("SELECT document_id FROM document_version WHERE id=?",Long.class,version);
        var document=jdbc.queryForMap("SELECT * FROM document WHERE id=? FOR UPDATE",doc);
        var tasks=jdbc.queryForList("SELECT * FROM document_processing_task WHERE document_version_id=? AND task_type='PARSE' FOR UPDATE",version);
        var task=tasks.getFirst();
        if(!"PENDING".equals(task.get("status"))) return null;
        long taskId=((Number)task.get("id")).longValue();
        if(document.get("deleted_at")!=null) {
            jdbc.update("UPDATE document_processing_task SET status='FAILED',error_code='DOCUMENT_DELETED',updated_at=? WHERE id=?",now(),taskId);
            jdbc.update("UPDATE document_version SET processing_status='FAILED' WHERE id=?",version); return null;
        }
        String token=UUID.randomUUID().toString();
        jdbc.update("UPDATE document_processing_task SET status='PROCESSING',attempts=attempts+1,run_token=?,lease_until=?,updated_at=? WHERE id=?",token,now().plusSeconds(timeout+30),now(),taskId);
        jdbc.update("UPDATE document_version SET processing_status='PROCESSING' WHERE id=?",version);
        var v=jdbc.queryForMap("SELECT * FROM document_version WHERE id=?",version);
        return new Work(taskId,version,doc,token,(String)v.get("storage_key"),(String)v.get("content_sha256"),(String)v.get("content_type"),((Number)v.get("size_bytes")).longValue());
    }
    private boolean current(Work work) {
        var d=jdbc.queryForMap("SELECT deleted_at FROM document WHERE id=? FOR UPDATE",work.documentId());
        var t=jdbc.queryForMap("SELECT status,run_token FROM document_processing_task WHERE id=? FOR UPDATE",work.taskId());
        return d.get("deleted_at")==null && "PROCESSING".equals(t.get("status")) && work.token().equals(t.get("run_token"));
    }
    public void validate(Work work,Result result) {
        if(result==null || result.taskId()!=work.taskId() || result.documentVersionId()!=work.documentVersionId() || !work.contentSha256().equals(result.contentSha256())
           || result.parserVersion()==null || result.parserVersion().length()>100 || result.pages()==null || result.pages().isEmpty() || result.pages().size()>2000)
            throw new IllegalArgumentException("Invalid parser identity or source count");
        long chars=0; boolean nonempty=false;
        for(int i=0;i<result.pages().size();i++) {
            var p=result.pages().get(i);
            if(p==null || p.sourceIndex()!=i || p.text()==null || p.text().length()>100000 || p.location()==null || p.location().length()>255
               || p.sectionTitle()!=null && p.sectionTitle().length()>255 || p.pageNumber()!=null && (p.pageNumber()<1 || p.pageNumber()>100)
               || !Set.of("TEXT","OCR","DOCX").contains(p.method()==null?"":p.method()) || p.confidence()!=null && (!Double.isFinite(p.confidence()) || p.confidence()<0 || p.confidence()>1)
               || p.warnings()==null || p.warnings().size()>10 || p.warnings().stream().anyMatch(w->!Set.of("EMPTY_SOURCE","LOW_CONFIDENCE").contains(w)))
                throw new IllegalArgumentException("Invalid parser source");
            chars+=p.text().length(); nonempty|=!p.text().isBlank();
            if(work.contentType().contains("wordprocessingml") ? p.pageNumber()!=null : p.pageNumber()==null || p.pageNumber()!=i+1)
                throw new IllegalArgumentException("Invalid page map");
        }
        if(chars>2000000 || !nonempty) throw new IllegalArgumentException("Empty or oversized parser text");
    }
    @Transactional
    public void complete(Work work,Result result) {
        validate(work,result);
        if(!current(work)) { fail(work,"DOCUMENT_DELETED"); return; }
        jdbc.update("DELETE FROM document_parse_source WHERE document_version_id=?",work.documentVersionId());
        for(var p:result.pages()) {
            try {
                jdbc.update("INSERT INTO document_parse_source(document_version_id,source_index,page_number,location,section_title,text_content,extraction_method,confidence,warnings_json) VALUES(?,?,?,?,?,?,?,?,?)",
                    work.documentVersionId(),p.sourceIndex(),p.pageNumber(),p.location(),p.sectionTitle(),p.text(),p.method(),p.confidence(),json.writeValueAsString(p.warnings()));
            } catch(com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalArgumentException("Invalid warnings"); }
        }
        jdbc.update("UPDATE document_processing_task SET status='SUCCEEDED',error_code=NULL,parser_version=?,run_token=NULL,lease_until=NULL,updated_at=? WHERE id=?",result.parserVersion(),now(),work.taskId());
        jdbc.update("UPDATE document_version SET processing_status='PARSED' WHERE id=?",work.documentVersionId());
    }
    @Transactional
    public void fail(Work work,String code) {
        // Always allow deleted documents to terminate their active task, but discard their text.
        jdbc.queryForList("SELECT id FROM document WHERE id=? FOR UPDATE",work.documentId());
        if(jdbc.update("UPDATE document_processing_task SET status='FAILED',error_code=?,run_token=NULL,lease_until=NULL,updated_at=? WHERE id=? AND status='PROCESSING' AND run_token=?",code,now(),work.taskId(),work.token())==1)
            jdbc.update("UPDATE document_version SET processing_status='FAILED' WHERE id=?",work.documentVersionId());
    }
}
