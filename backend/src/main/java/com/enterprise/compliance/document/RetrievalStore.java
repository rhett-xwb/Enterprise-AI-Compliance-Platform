package com.enterprise.compliance.document;

import com.enterprise.compliance.auth.PlatformPrincipal;
import com.enterprise.compliance.common.BusinessException;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RetrievalStore {
    public record Hit(long documentId,long documentVersionId,long chunkId,String title,String versionNo,Integer pageNumber,String sectionTitle,String location,int sourceIndex,int startOffset,int endOffset,String text,double score) {}
    private final JdbcTemplate jdbc;
    public RetrievalStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    // Use fresh transactions before and after the remote call. Never authorize from vector payloads.
    private static final String VISIBLE="""
        FROM document d JOIN document_version v ON v.id=d.current_version_id
        JOIN document_chunk c ON c.document_version_id=v.id
        JOIN document_index_run ir ON ir.document_version_id=v.id AND ir.generation=v.index_generation
        WHERE d.deleted_at IS NULL AND d.knowledge_domain='POLICY'
        AND v.publication_status='PUBLISHED' AND v.processing_status='READY'
        AND v.effective_at<=? AND (v.expires_at IS NULL OR v.expires_at>?)
        AND ir.status='SUCCEEDED' AND c.index_status='INDEXED' AND c.embedding_model=?
        AND NOT EXISTS(SELECT 1 FROM document_chunk bad WHERE bad.document_version_id=v.id AND (bad.index_status<>'INDEXED' OR bad.vector_key IS NULL))
        AND EXISTS(SELECT 1 FROM document_role_access a JOIN sys_user_role ur ON ur.role_id=a.role_id WHERE a.document_id=d.id AND ur.user_id=?)
        AND (NOT EXISTS(SELECT 1 FROM document_department_access a WHERE a.document_id=d.id)
             OR EXISTS(SELECT 1 FROM document_department_access a WHERE a.document_id=d.id AND a.department_id=?))
        AND EXISTS(SELECT 1 FROM sys_user u WHERE u.id=? AND u.enabled=1 AND u.deleted_at IS NULL)
        """;
    private Object[] args(PlatformPrincipal user) {
        var now=LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        Long department=jdbc.queryForObject("SELECT department_id FROM sys_user WHERE id=?",Long.class,user.id());
        return new Object[]{now,now,RetrievalClient.MODEL,user.id(),department,user.id()};
    }
    @Transactional(readOnly=true)
    public List<String> keys(PlatformPrincipal user) {
        var keys=jdbc.queryForList("SELECT c.vector_key "+VISIBLE+" LIMIT 10001",String.class,args(user));
        if(keys.size()>10000) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"可检索文本块超过本地模式容量，请升级检索配置");
        return keys;
    }
    @Transactional(readOnly=true)
    public List<Hit> resolve(PlatformPrincipal user,LinkedHashMap<String,Double> scores) {
        if(scores.isEmpty()) return List.of();
        var values=new ArrayList<>(Arrays.asList(args(user))); values.addAll(scores.keySet());
        var hits=jdbc.query("SELECT d.id AS doc_id,d.title,v.id AS version_id,v.version_no,c.* "+VISIBLE+" AND c.vector_key IN ("+String.join(",",Collections.nCopies(scores.size(),"?"))+")",(rs,n)->new Hit(
            rs.getLong("doc_id"),rs.getLong("version_id"),rs.getLong("id"),rs.getString("title"),rs.getString("version_no"),rs.getObject("page_number",Integer.class),rs.getString("section_title"),rs.getString("source_location"),rs.getInt("source_index"),rs.getInt("start_offset"),rs.getInt("end_offset"),rs.getString("text_content"),scores.get(rs.getString("vector_key"))),values.toArray());
        hits.sort(Comparator.comparingDouble((Hit item) -> item.score()).reversed()); return hits;
    }
    @Transactional(readOnly=true)
    public boolean stillCurrent(PlatformPrincipal user,List<Hit> evidence) {
        for(var hit:evidence) {
            var values=new ArrayList<>(Arrays.asList(args(user))); values.add(hit.chunkId()); values.add(hit.documentVersionId()); values.add(hit.text());
            if(jdbc.queryForObject("SELECT COUNT(*) "+VISIBLE+" AND c.id=? AND v.id=? AND BINARY c.text_content=BINARY ?",Integer.class,values.toArray())!=1) return false;
        }
        return true;
    }
    @Transactional(readOnly=true)
    public String historicalAccess(PlatformPrincipal user,Hit hit) {
        // No governance bypass: history uses the same current role and department ACL as retrieval.
        Long department=jdbc.queryForObject("SELECT department_id FROM sys_user WHERE id=?",Long.class,user.id());
        var rows=jdbc.queryForList("""
            SELECT (d.current_version_id=v.id AND v.publication_status='PUBLISHED' AND v.processing_status='READY'
              AND v.effective_at<=? AND (v.expires_at IS NULL OR v.expires_at>?)) AS current_source
            FROM document d JOIN document_version v ON v.document_id=d.id
            WHERE d.id=? AND v.id=? AND d.deleted_at IS NULL AND v.published_at IS NOT NULL
            AND EXISTS(SELECT 1 FROM document_role_access a JOIN sys_user_role ur ON ur.role_id=a.role_id WHERE a.document_id=d.id AND ur.user_id=?)
            AND (NOT EXISTS(SELECT 1 FROM document_department_access a WHERE a.document_id=d.id)
                OR EXISTS(SELECT 1 FROM document_department_access a WHERE a.document_id=d.id AND a.department_id=?))
            AND EXISTS(SELECT 1 FROM sys_user WHERE id=? AND enabled=1 AND deleted_at IS NULL)
            """,Integer.class,LocalDateTime.now(ZoneId.of("Asia/Shanghai")),LocalDateTime.now(ZoneId.of("Asia/Shanghai")),hit.documentId(),hit.documentVersionId(),user.id(),department,user.id());
        return rows.isEmpty()?"UNAVAILABLE":rows.getFirst()==1?"CURRENT":"OUTDATED";
    }
}
