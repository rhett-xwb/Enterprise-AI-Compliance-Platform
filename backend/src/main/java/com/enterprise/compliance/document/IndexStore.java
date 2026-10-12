package com.enterprise.compliance.document;

import com.enterprise.compliance.auth.PlatformPrincipal;
import com.enterprise.compliance.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IndexStore {
    public record State(String status,int attempts,String errorCode,String generation,String embeddingModel) {}
    public record Chunk(String key,String text,int sourceIndex,Integer pageNumber,String location,String sectionTitle) {}
    public record Work(long documentId,long documentVersionId,String generation,List<String> roles,List<Long> departmentIds,List<Chunk> chunks,List<PolicyChunker.Slice> slices,long actorId) {}
    private final JdbcTemplate jdbc;
    private final DocumentService docs;
    private final ParsingStore parsing;
    public IndexStore(JdbcTemplate jdbc,DocumentService docs,ParsingStore parsing) { this.jdbc=jdbc; this.docs=docs; this.parsing=parsing; }
    private LocalDateTime now() { return LocalDateTime.now(ZoneId.of("Asia/Shanghai")); }
    private void check(long id,long version,PlatformPrincipal user) {
        docs.requireManaged(id,user);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM document_version WHERE id=? AND document_id=?",Integer.class,version,id)==0)
            throw new BusinessException(HttpStatus.NOT_FOUND,"版本不存在或无访问权限");
    }
    private BusinessException conflict(String message) { return new BusinessException(HttpStatus.CONFLICT,message); }
    @Transactional
    public State state(long id,long version,PlatformPrincipal user) {
        check(id,version,user);
        var rows=jdbc.query("SELECT * FROM document_index_run WHERE document_version_id=?",(rs,n)->new State(rs.getString("status"),rs.getInt("attempts"),rs.getString("error_code"),rs.getString("generation"),rs.getString("embedding_model")),version);
        if(rows.isEmpty()) return null;
        var state=rows.getFirst();
        if("PROCESSING".equals(state.status()) && java.util.Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM document_index_run WHERE document_version_id=? AND lease_until<? AND NOT EXISTS (SELECT 1 FROM document_async_job WHERE document_version_id=? AND task_type='INDEX' AND status IN ('PENDING','PROCESSING'))",Integer.class,version,now(),version))>0) {
            jdbc.update("UPDATE document_index_run SET status='FAILED',error_code='INDEX_TIMEOUT',lease_until=NULL,updated_at=? WHERE document_version_id=?",now(),version);
            return new State("FAILED",state.attempts(),"INDEX_TIMEOUT",state.generation(),state.embeddingModel());
        }
        return state;
    }
    @Transactional
    public Work prepare(long id,long version,PlatformPrincipal user,boolean rebuild) {
        check(id,version,user);
        var previous=state(id,version,user);
        if(previous!=null && "PROCESSING".equals(previous.status())) throw conflict("索引正在处理，请稍后刷新");
        if(previous!=null && "SUCCEEDED".equals(previous.status()) && !rebuild) return null;
        var row=jdbc.queryForMap("SELECT * FROM document_version WHERE id=?",version);
        if(!Set.of("DRAFT","PUBLISHED").contains(row.get("publication_status"))) throw conflict("停用版本不能建立索引");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM document_processing_task WHERE document_version_id=? AND task_type='PARSE' AND status='SUCCEEDED'",Integer.class,version)==0)
            throw conflict("请先成功解析正文");
        var slices=PolicyChunker.split(parsing.sources(id,version,user));
        String generation=UUID.randomUUID().toString();
        var chunks=new ArrayList<Chunk>();
        for(var s:slices) chunks.add(new Chunk(UUID.randomUUID().toString(),s.text(),s.sourceIndex(),s.pageNumber(),s.location(),s.sectionTitle()));
        jdbc.update("INSERT INTO document_index_run(document_version_id,generation,status,lease_until) VALUES(?,?,'PROCESSING',?) ON DUPLICATE KEY UPDATE generation=VALUES(generation),status='PROCESSING',attempts=attempts+1,error_code=NULL,lease_until=VALUES(lease_until),updated_at=?",version,generation,now().plusSeconds(210),now());
        jdbc.update("UPDATE document_version SET processing_status='PARSED',index_generation=NULL WHERE id=?",version);
        jdbc.update("UPDATE document_chunk SET index_status='INACTIVE' WHERE document_version_id=?",version);
        jdbc.update("INSERT INTO document_audit(document_id,version_id,actor_id,action,detail_json) VALUES(?,?,?,'INDEX_REQUEST',JSON_OBJECT('generation',?,'rebuild',?))",id,version,user.id(),generation,rebuild);
        var detail=docs.detail(id,user);
        return new Work(id,version,generation,detail.roles(),detail.departmentIds(),chunks,slices,user.id());
    }
    @Transactional
    public void complete(Work work,JsonNode reply) {
        if(!work.generation().equals(reply.path("generation").asText()) || !RetrievalClient.MODEL.equals(reply.path("model").asText()) || !reply.path("keys").isArray() || reply.path("keys").size()!=work.chunks().size()) throw new IllegalArgumentException("INVALID_INDEX_RESPONSE");
        for(int i=0;i<work.chunks().size();i++) if(!work.chunks().get(i).key().equals(reply.path("keys").get(i).asText())) throw new IllegalArgumentException("INVALID_INDEX_KEYS");
        var doc=jdbc.queryForMap("SELECT deleted_at FROM document WHERE id=? FOR UPDATE",work.documentId());
        var state=jdbc.queryForMap("SELECT * FROM document_index_run WHERE document_version_id=? FOR UPDATE",work.documentVersionId());
        if(!"PROCESSING".equals(state.get("status")) || !work.generation().equals(state.get("generation"))) return;
        if(java.util.Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM document_index_run WHERE document_version_id=? AND lease_until<?",Integer.class,work.documentVersionId(),now()))>0) { fail(work,"INDEX_TIMEOUT"); return; }
        if(doc.get("deleted_at")!=null || "RETIRED".equals(jdbc.queryForObject("SELECT publication_status FROM document_version WHERE id=?",String.class,work.documentVersionId()))) { fail(work,"DOCUMENT_INACTIVE"); return; }
        jdbc.update("DELETE FROM document_chunk WHERE document_version_id=?",work.documentVersionId());
        for(int i=0;i<work.slices().size();i++) {
            var s=work.slices().get(i);
            jdbc.update("INSERT INTO document_chunk(document_version_id,chunk_index,page_number,section_title,text_content,embedding_model,vector_key,index_status,source_index,source_location,start_offset,end_offset) VALUES(?,?,?,?,?,?,?,'INDEXED',?,?,?,?)",work.documentVersionId(),i,s.pageNumber(),s.sectionTitle(),s.text(),RetrievalClient.MODEL,work.chunks().get(i).key(),s.sourceIndex(),s.location(),s.start(),s.end());
        }
        jdbc.update("UPDATE document_version SET processing_status='READY',index_generation=? WHERE id=?",work.generation(),work.documentVersionId());
        jdbc.update("UPDATE document_index_run SET status='SUCCEEDED',embedding_model=?,error_code=NULL,lease_until=NULL,updated_at=? WHERE document_version_id=?",RetrievalClient.MODEL,now(),work.documentVersionId());
        jdbc.update("INSERT INTO document_audit(document_id,version_id,actor_id,action,detail_json) VALUES(?,?,?,'INDEX_COMPLETE',JSON_OBJECT('generation',?))",work.documentId(),work.documentVersionId(),work.actorId(),work.generation());
    }
    @Transactional
    public void fail(Work work,String code) {
        jdbc.queryForList("SELECT id FROM document WHERE id=? FOR UPDATE",work.documentId());
        jdbc.update("UPDATE document_index_run SET status='FAILED',error_code=?,lease_until=NULL,updated_at=? WHERE document_version_id=? AND generation=? AND status='PROCESSING'",code,now(),work.documentVersionId(),work.generation());
        // PARSED and inactive chunks are intentionally retained for a safe manual retry.
    }
}
