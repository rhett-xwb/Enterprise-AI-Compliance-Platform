package com.enterprise.compliance.document;

import com.enterprise.compliance.auth.PlatformPrincipal;
import com.enterprise.compliance.common.BusinessException;
import com.enterprise.compliance.user.dto.PageResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentService {
    public record Metadata(@NotBlank @Size(max=255) String title,
            @NotEmpty @Size(max=3) List<@NotBlank String> roles,
            @NotNull @Size(max=100) List<@Positive Long> departmentIds) {}
    public record VersionInput(@NotBlank @Pattern(regexp="[a-zA-Z0-9._-]{1,32}") String versionNo,
            @NotNull LocalDateTime effectiveAt, LocalDateTime expiresAt) {}
    public record CreateInput(@NotNull @jakarta.validation.Valid Metadata document,
            @NotNull @jakarta.validation.Valid VersionInput version) {}
    public record DocumentRow(long id, String title, long ownerId, Long currentVersionId, String currentVersionNo, LocalDateTime createdAt) {}
    public record Version(long id, String versionNo, String originalFilename, String contentType, long sizeBytes,
            String contentSha256, String publicationStatus, String processingStatus, LocalDateTime effectiveAt,
            LocalDateTime expiresAt, LocalDateTime publishedAt, LocalDateTime createdAt, boolean searchable) {}
    public record Detail(DocumentRow document, List<String> roles, List<Long> departmentIds, List<Version> versions, boolean manageable) {}
    public record Audit(long id, long actorId, Long versionId, String action, String detailJson, LocalDateTime createdAt) {}
    public record Download(String filename, String type, byte[] bytes) {}
    private final JdbcTemplate jdbc;
    private final FileStorage storage;
    private final UploadValidator validator;
    private final ObjectMapper json;
    public DocumentService(JdbcTemplate jdbc, FileStorage storage, UploadValidator validator, ObjectMapper json) {
        this.jdbc=jdbc; this.storage=storage; this.validator=validator; this.json=json;
    }
    private static final String ACL = """
        EXISTS (SELECT 1 FROM document_role_access a JOIN sys_user_role ur ON ur.role_id=a.role_id
                WHERE a.document_id=d.id AND ur.user_id=?)
        AND (NOT EXISTS (SELECT 1 FROM document_department_access da WHERE da.document_id=d.id)
             OR EXISTS (SELECT 1 FROM document_department_access da WHERE da.document_id=d.id AND da.department_id=?))
        """;
    // Future retrieval must join this authoritative SQL visibility gate; vectors alone are not access control.
    private static final String ELIGIBLE = """
        v.publication_status='PUBLISHED' AND v.processing_status='READY'
        AND v.effective_at<=? AND (v.expires_at IS NULL OR v.expires_at>?)
        AND EXISTS (SELECT 1 FROM document_chunk c WHERE c.document_version_id=v.id)
        AND NOT EXISTS (SELECT 1 FROM document_chunk c WHERE c.document_version_id=v.id
            AND (c.index_status<>'INDEXED' OR c.vector_key IS NULL OR TRIM(c.vector_key)='' OR c.embedding_model IS NULL OR TRIM(c.embedding_model)='' OR TRIM(c.text_content)=''))
        """;
    private LocalDateTime now() { return LocalDateTime.now(ZoneId.of("Asia/Shanghai")); }
    private boolean manages(PlatformPrincipal user, DocumentRow doc) {
        return user.permissions().contains("document:manage") && (user.roles().contains("ADMIN") || doc.ownerId()==user.id());
    }
    private BusinessException missing() { return new BusinessException(HttpStatus.NOT_FOUND,"制度不存在或无访问权限"); }
    private BusinessException conflict(String message) { return new BusinessException(HttpStatus.CONFLICT,message); }
    private BusinessException invalid(String message) { return new BusinessException(HttpStatus.BAD_REQUEST,message); }
    private DocumentRow row(java.sql.ResultSet rs) throws SQLException {
        return new DocumentRow(rs.getLong("id"),rs.getString("title"),rs.getLong("owner_id"),
            rs.getObject("current_version_id",Long.class),rs.getString("current_version_no"),rs.getTimestamp("created_at").toLocalDateTime());
    }
    private DocumentRow document(long id, boolean lock) {
        var rows=jdbc.query("SELECT d.*, (SELECT version_no FROM document_version WHERE id=d.current_version_id) AS current_version_no FROM document d WHERE d.id=? AND d.deleted_at IS NULL"+(lock?" FOR UPDATE":""),(rs,n)->row(rs),id);
        if(rows.isEmpty()) throw missing(); return rows.getFirst();
    }
    private DocumentRow managed(long id, PlatformPrincipal user) {
        var doc=document(id,true); if(!manages(user,doc)) throw missing(); return doc;
    }
    // Parser governance uses the same owner/admin gate and document lock as publication.
    public void requireManaged(long id, PlatformPrincipal user) { managed(id,user); }
    private void checkRead(DocumentRow doc, PlatformPrincipal user) {
        if(manages(user,doc)) return;
        if(!user.permissions().contains("document:read") || jdbc.queryForObject("SELECT COUNT(*) FROM document d WHERE d.id=? AND "+ACL,Integer.class,doc.id(),user.id(),user.departmentId())==0) throw missing();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM document_version WHERE document_id=? AND published_at IS NOT NULL",Integer.class,doc.id())==0) throw missing();
    }
    @Transactional(readOnly=true)
    public PageResponse<DocumentRow> list(PlatformPrincipal user, boolean management, long page, long size) {
        if(page<1 || page>1_000_000 || size<1 || size>100) throw invalid("分页参数无效");
        String from; List<Object> args=new ArrayList<>();
        if(management) {
            if(!user.permissions().contains("document:manage")) throw new BusinessException(HttpStatus.FORBIDDEN,"没有制度管理权限");
            from=" FROM document d WHERE d.deleted_at IS NULL";
            if(!user.roles().contains("ADMIN")) { from+=" AND d.owner_id=?"; args.add(user.id()); }
        } else {
            if(!user.permissions().contains("document:read")) throw new BusinessException(HttpStatus.FORBIDDEN,"没有制度查询权限");
            var time=now(); from=" FROM document d JOIN document_version v ON v.id=d.current_version_id WHERE d.deleted_at IS NULL AND "+ACL+" AND "+ELIGIBLE;
            args.add(user.id()); args.add(user.departmentId()); args.add(time); args.add(time);
        }
        long total=Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,args.toArray()));
        args.add(size); args.add((page-1)*size);
        var rows=jdbc.query("SELECT d.*, (SELECT version_no FROM document_version WHERE id=d.current_version_id) AS current_version_no"+from+" ORDER BY d.id DESC LIMIT ? OFFSET ?",(rs,n)->row(rs),args.toArray());
        return new PageResponse<>(page,size,total,rows);
    }
    private LocalDateTime date(ResultSet rs,String name) throws SQLException {
        var value=rs.getTimestamp(name); return value==null?null:value.toLocalDateTime();
    }
    private List<Version> versions(DocumentRow doc, boolean manager) {
        var time=now();
        return jdbc.query("SELECT v.*, (v.id=? AND "+ELIGIBLE+") AS searchable FROM document_version v WHERE v.document_id=?"+(manager?"":" AND v.published_at IS NOT NULL")+" ORDER BY v.id DESC",(rs,n)->new Version(
            rs.getLong("id"),rs.getString("version_no"),rs.getString("original_filename"),rs.getString("content_type"),rs.getLong("size_bytes"),rs.getString("content_sha256"),
            rs.getString("publication_status"),rs.getString("processing_status"),date(rs,"effective_at"),date(rs,"expires_at"),date(rs,"published_at"),date(rs,"created_at"),rs.getBoolean("searchable")),doc.currentVersionId(),time,time,doc.id());
    }
    @Transactional(readOnly=true)
    public Detail detail(long id, PlatformPrincipal user) {
        var doc=document(id,false); checkRead(doc,user);
        return new Detail(doc,jdbc.queryForList("SELECT r.code FROM document_role_access a JOIN sys_role r ON r.id=a.role_id WHERE a.document_id=? ORDER BY r.code",String.class,id),
            jdbc.queryForList("SELECT department_id FROM document_department_access WHERE document_id=? ORDER BY department_id",Long.class,id),versions(doc,manages(user,doc)),manages(user,doc));
    }
    private void validateMetadata(Metadata input) {
        if(input.roles().stream().anyMatch(r->!Set.of("ADMIN","LEGAL","EMPLOYEE").contains(r))) throw invalid("角色范围无效");
        for(Long id:new HashSet<>(input.departmentIds())) if(jdbc.queryForObject("SELECT COUNT(*) FROM sys_department WHERE id=?",Integer.class,id)==0) throw invalid("部门不存在");
    }
    private void setAccess(long id, Metadata input) {
        jdbc.update("DELETE FROM document_role_access WHERE document_id=?",id);
        jdbc.update("DELETE FROM document_department_access WHERE document_id=?",id);
        for(String role:new HashSet<>(input.roles())) jdbc.update("INSERT INTO document_role_access(document_id,role_id) SELECT ?,id FROM sys_role WHERE code=?",id,role);
        for(Long department:new HashSet<>(input.departmentIds())) jdbc.update("INSERT INTO document_department_access(document_id,department_id) VALUES(?,?)",id,department);
    }
    private long insert(String sql,Object...args) {
        var key=new GeneratedKeyHolder();
        jdbc.update(connection->{var statement=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS); for(int i=0;i<args.length;i++) statement.setObject(i+1,args[i]); return java.util.Objects.requireNonNull(statement);},key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    private void audit(long id,Long version,PlatformPrincipal user,String action,Object details) {
        try { jdbc.update("INSERT INTO document_audit(document_id,version_id,actor_id,action,detail_json) VALUES(?,?,?,?,?)",id,version,user.id(),action,json.writeValueAsString(details)); }
        catch(com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("Unable to encode audit"); }
    }
    @Transactional
    public Detail create(CreateInput input, MultipartFile file, PlatformPrincipal user) {
        validateMetadata(input.document());
        long id=insert("INSERT INTO document(title,knowledge_domain,owner_id) VALUES(?,'POLICY',?)",input.document().title().trim(),user.id());
        setAccess(id,input.document());
        audit(id,null,user,"CREATE",input.document());
        upload(id,input.version(),file,user);
        return detail(id,user);
    }
    @Transactional
    public Detail upload(long id,VersionInput input,MultipartFile file,PlatformPrincipal user) {
        managed(id,user);
        if(input.expiresAt()!=null && !input.expiresAt().isAfter(input.effectiveAt())) throw invalid("失效时间必须晚于生效时间");
        if(java.util.Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM document_version WHERE document_id=? AND version_no=?",Integer.class,id,input.versionNo()))>0) throw conflict("该制度版本号已存在");
        var content=validator.validate(file);
        String key=storage.store(content.bytes());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCompletion(int status) { if(status!=STATUS_COMMITTED) storage.remove(key); }
        });
        long version=insert("""
            INSERT INTO document_version(document_id,version_no,storage_key,original_filename,content_sha256,content_type,size_bytes,effective_at,expires_at,creator_id)
            VALUES(?,?,?,?,?,?,?,?,?,?)
            """,id,input.versionNo(),key,content.filename(),content.sha256(),content.contentType(),content.bytes().length,input.effectiveAt(),input.expiresAt(),user.id());
        audit(id,version,user,"UPLOAD",Map.of("versionNo",input.versionNo(),"sha256",content.sha256()));
        return detail(id,user);
    }
    @Transactional
    public Detail update(long id,Metadata input,PlatformPrincipal user) {
        managed(id,user); validateMetadata(input);
        jdbc.update("UPDATE document SET title=? WHERE id=?",input.title().trim(),id); setAccess(id,input);
        audit(id,null,user,"UPDATE_SCOPE",input); return detail(id,user);
    }
    @Transactional
    public Detail publish(long id,long versionId,PlatformPrincipal user) {
        var doc=managed(id,user);
        var rows=jdbc.queryForList("SELECT * FROM document_version WHERE id=? AND document_id=?",versionId,id);
        if(rows.isEmpty()) throw missing(); var v=rows.getFirst();
        if(Objects.equals(doc.currentVersionId(),versionId) && "PUBLISHED".equals(v.get("publication_status"))) return detail(id,user);
        if(!"DRAFT".equals(v.get("publication_status"))) throw conflict("只有草稿版本可以发布");
        if(!"READY".equals(v.get("processing_status"))) throw conflict("版本尚未完成解析和索引，不能发布");
        var time=now();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM document_version WHERE id=? AND effective_at<=? AND (expires_at IS NULL OR expires_at>?)",Integer.class,versionId,time,time)==0) throw conflict("版本尚未生效或已过期");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM document_chunk WHERE document_version_id=?",Integer.class,versionId)==0 || java.util.Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM document_chunk WHERE document_version_id=? AND (index_status<>'INDEXED' OR vector_key IS NULL OR TRIM(vector_key)='' OR embedding_model IS NULL OR TRIM(embedding_model)='' OR TRIM(text_content)='')",Integer.class,versionId))>0) throw conflict("文本块或检索索引尚未就绪，不能发布");
        if(doc.currentVersionId()!=null) {
            jdbc.update("UPDATE document_version SET publication_status='RETIRED' WHERE id=?",doc.currentVersionId());
            jdbc.update("UPDATE document_chunk SET index_status='INACTIVE' WHERE document_version_id=?",doc.currentVersionId());
        }
        jdbc.update("UPDATE document_version SET publication_status='PUBLISHED',published_at=? WHERE id=?",time,versionId);
        jdbc.update("UPDATE document SET current_version_id=? WHERE id=?",versionId,id);
        audit(id,versionId,user,"PUBLISH",Map.of("previousVersionId",doc.currentVersionId()==null?0:doc.currentVersionId()));
        return detail(id,user);
    }
    @Transactional
    public Detail retire(long id,long versionId,PlatformPrincipal user) {
        var doc=managed(id,user);
        if(!Objects.equals(doc.currentVersionId(),versionId)) throw conflict("只能停用当前发布版本");
        jdbc.update("UPDATE document_version SET publication_status='RETIRED' WHERE document_id=? AND id=?",id,versionId);
        jdbc.update("UPDATE document_chunk SET index_status='INACTIVE' WHERE document_version_id=?",versionId);
        jdbc.update("UPDATE document SET current_version_id=NULL WHERE id=?",id);
        audit(id,versionId,user,"RETIRE",Map.of()); return detail(id,user);
    }
    @Transactional
    public void delete(long id,PlatformPrincipal user) {
        var doc=managed(id,user);
        jdbc.update("UPDATE document_version SET publication_status='RETIRED' WHERE document_id=? AND publication_status='PUBLISHED'",id);
        jdbc.update("UPDATE document_chunk SET index_status='INACTIVE' WHERE document_version_id IN (SELECT id FROM document_version WHERE document_id=?)",id);
        jdbc.update("UPDATE document SET deleted_at=?,current_version_id=NULL WHERE id=?",now(),id);
        audit(id,doc.currentVersionId(),user,"DELETE",Map.of());
    }
    @Transactional(readOnly=true)
    public List<Audit> history(long id,PlatformPrincipal user) {
        var doc=document(id,false); if(!manages(user,doc)) throw missing();
        return jdbc.query("SELECT * FROM document_audit WHERE document_id=? ORDER BY id DESC LIMIT 200",(rs,n)->new Audit(rs.getLong("id"),rs.getLong("actor_id"),rs.getObject("version_id",Long.class),rs.getString("action"),rs.getString("detail_json"),date(rs,"created_at")),id);
    }
    @Transactional(readOnly=true)
    public Download download(long id,long versionId,PlatformPrincipal user) {
        var doc=document(id,false); checkRead(doc,user);
        var rows=jdbc.queryForList("SELECT * FROM document_version WHERE document_id=? AND id=?"+(manages(user,doc)?"":" AND published_at IS NOT NULL"),id,versionId);
        if(rows.isEmpty()) throw missing(); var v=rows.getFirst();
        byte[] bytes=storage.read((String)v.get("storage_key"));
        try {
            if(bytes.length!=((Number)v.get("size_bytes")).longValue() || !HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)).equals(v.get("content_sha256"))) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"原文件完整性检查失败，请联系管理员");
        } catch(java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
        return new Download((String)v.get("original_filename"),(String)v.get("content_type"),bytes);
    }
}
