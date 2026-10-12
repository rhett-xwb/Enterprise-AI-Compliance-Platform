package com.enterprise.compliance;

import com.enterprise.compliance.auth.*;
import com.enterprise.compliance.document.*;
import com.enterprise.compliance.user.dto.CreateUserRequest;
import com.enterprise.compliance.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.awt.image.BufferedImage;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="app.bootstrap.enabled=false") @AutoConfigureMockMvc @Transactional
@EnabledIfEnvironmentVariable(named="TEST_DB_URL",matches=".+")
class DocumentIntegrationTest extends MySqlTestSupport {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired UserService users;
    @Autowired AuthService auth; @Autowired DocumentService docs; @Autowired FileStorage storage; @Autowired ObjectMapper json;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    private static final byte[] PDF="%PDF-1.7\n1 0 obj <<>> endobj\ntrailer <<>>\n%%EOF".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private record Account(long id,String token,PlatformPrincipal principal) {}
    private Account account(String role,Long department) {
        String username="doc_"+UUID.randomUUID().toString().replace("-","");
        long id=users.create(new CreateUserRequest(username,"document-test-password-only",username,null,department)).id();
        jdbc.update("DELETE FROM sys_user_role WHERE user_id=?",id);
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT ?,id FROM sys_role WHERE code=?",id,role);
        var permissions=jdbc.queryForList("SELECT p.code FROM sys_role r JOIN sys_role_permission rp ON rp.role_id=r.id JOIN sys_permission p ON p.id=rp.permission_id WHERE r.code=?",String.class,role);
        return new Account(id,auth.login(username,"document-test-password-only").accessToken(),new PlatformPrincipal(id,username,department,List.of(role),permissions,"test"));
    }
    private DocumentService.VersionInput input(String v) { return new DocumentService.VersionInput(v,LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusDays(1),null); }
    private MockMultipartFile file(String name,byte[] bytes) { return new MockMultipartFile("file",name,"application/octet-stream",bytes); }
    private MockMultipartFile metadata(Object value) throws Exception { return new MockMultipartFile("metadata","","application/json",json.writeValueAsBytes(value)); }
    private DocumentService.Detail create(Account owner,List<String> roles,List<Long> departments) {
        return docs.create(new DocumentService.CreateInput(new DocumentService.Metadata("差旅制度",roles,departments),input("v1")),file("制度.pdf",PDF),owner.principal());
    }
    private void ready(long v) {
        jdbc.update("UPDATE document_version SET processing_status='READY' WHERE id=?",v);
        jdbc.update("INSERT INTO document_chunk(document_version_id,chunk_index,text_content,index_status,vector_key,embedding_model) VALUES(?,0,'验收条款','INDEXED',?,'test-embedding')",v,"fixture-"+v);
    }
    private String bearer(Account a) { return "Bearer "+a.token(); }
    @Test void uploadFormatsValidateContentAndHideStorage() throws Exception {
        var owner=account("LEGAL",null);
        var response=mvc.perform(multipart("/api/documents").file(metadata(new DocumentService.CreateInput(new DocumentService.Metadata("测试制度",List.of("EMPLOYEE"),List.of()),input("v1")))).file(file("../../制度.pdf",PDF)).header("Authorization",bearer(owner)))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.versions[0].originalFilename").value("制度.pdf")).andExpect(jsonPath("$.data.versions[0].processingStatus").value("PENDING"))
            .andExpect(jsonPath("$.data.versions[0].storageKey").doesNotExist()).andReturn().getResponse().getContentAsString();
        long id=json.readTree(response).path("data").path("document").path("id").asLong();
        var image=new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB),"png",image);
        var jpeg=new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB),"jpeg",jpeg);
        var zipBytes=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(zipBytes)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml")); zip.write("<Types xmlns='http://schemas.openxmlformats.org/package/2006/content-types'><Override PartName='/word/document.xml' ContentType='application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml'/></Types>".getBytes()); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml")); zip.write("<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:body/></w:document>".getBytes()); zip.closeEntry();
        }
        for(var sample:List.of(file("制度.png",image.toByteArray()),file("制度.jpg",jpeg.toByteArray()),file("制度.docx",zipBytes.toByteArray())))
            mvc.perform(multipart("/api/documents/{id}/versions",id).file(metadata(input("v"+UUID.randomUUID().toString().substring(0,8)))).file(sample).header("Authorization",bearer(owner))).andExpect(status().isCreated());
        for(var sample:List.of(file("bad.pdf","not-pdf".getBytes()),file("bad.exe",PDF),file("bad.jpg",image.toByteArray()),file("empty.pdf",new byte[0]),file("large.pdf",new byte[10*1024*1024+1]),file("bad.docx",PDF)))
            mvc.perform(multipart("/api/documents/{id}/versions",id).file(metadata(input("bad"))).file(sample).header("Authorization",bearer(owner))).andExpect(status().isBadRequest());
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM document_version WHERE document_id=?",Integer.class,id));
    }
    @Test void accessChecksDraftsRolesDepartmentsOwnersAndScopeChanges() throws Exception {
        jdbc.update("INSERT INTO sys_department(id,code,name) VALUES(901,'DOC_A','A'),(902,'DOC_B','B')");
        var owner=account("LEGAL",901L); var legal=account("LEGAL",901L); var employee=account("EMPLOYEE",901L); var outsider=account("EMPLOYEE",902L); var admin=account("ADMIN",null);
        var result=create(owner,List.of("EMPLOYEE"),List.of(901L)); long id=result.document().id(), v=result.versions().getFirst().id();
        mvc.perform(get("/api/documents/{id}",id).header("Authorization",bearer(employee))).andExpect(status().isNotFound());
        mvc.perform(get("/api/documents/{id}/versions/{v}/file",id,v).header("Authorization",bearer(legal))).andExpect(status().isNotFound());
        mvc.perform(get("/api/documents/{id}/versions/{v}/file",id,v).header("Authorization",bearer(admin))).andExpect(status().isOk()).andExpect(content().bytes(PDF)).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get("/api/documents").param("management","true").header("Authorization",bearer(employee))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/documents/{id}",id).header("Authorization",bearer(legal))).andExpect(status().isNotFound());
        ready(v); docs.publish(id,v,owner.principal());
        assertEquals(1,docs.list(employee.principal(),false,1,20).total());
        mvc.perform(get("/api/documents/{id}/versions/{v}/file",id,v).header("Authorization",bearer(employee))).andExpect(status().isOk());
        mvc.perform(get("/api/documents/{id}/versions/{v}/file",id,v).header("Authorization",bearer(outsider))).andExpect(status().isNotFound());
        docs.update(id,new DocumentService.Metadata("新名称",List.of("LEGAL"),List.of()),owner.principal());
        mvc.perform(get("/api/documents/{id}/versions/{v}/file",id,v).header("Authorization",bearer(employee))).andExpect(status().isNotFound());
        mvc.perform(get("/api/documents/{id}",id).header("Authorization",bearer(legal))).andExpect(status().isOk()).andExpect(jsonPath("$.data.manageable").value(false));
        mvc.perform(get("/api/documents/{id}/audit",id).header("Authorization",bearer(legal))).andExpect(status().isNotFound());
        jdbc.update("DELETE FROM document_role_access WHERE document_id=?",id);
        mvc.perform(get("/api/documents/{id}",id).header("Authorization",bearer(legal))).andExpect(status().isNotFound());
    }
    @Test void publishRequiresReadinessAndReplacementRetainsHistory() throws Exception {
        var owner=account("LEGAL",null); var reader=account("EMPLOYEE",null); var result=create(owner,List.of("EMPLOYEE"),List.of()); long id=result.document().id(), v1=result.versions().getFirst().id();
        mvc.perform(post("/api/documents/{id}/versions/{v}/publish",id,v1).header("Authorization",bearer(owner))).andExpect(status().isConflict());
        jdbc.update("UPDATE document_version SET processing_status='READY' WHERE id=?",v1);
        mvc.perform(post("/api/documents/{id}/versions/{v}/publish",id,v1).header("Authorization",bearer(owner))).andExpect(status().isConflict());
        ready(v1); docs.publish(id,v1,owner.principal()); docs.publish(id,v1,owner.principal());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM document_audit WHERE document_id=? AND action='PUBLISH'",Integer.class,id));
        long v2=docs.upload(id,input("v2"),file("新制度.pdf",PDF),owner.principal()).versions().getFirst().id(); ready(v2); docs.publish(id,v2,owner.principal());
        assertEquals("RETIRED",jdbc.queryForObject("SELECT publication_status FROM document_version WHERE id=?",String.class,v1));
        assertEquals("INACTIVE",jdbc.queryForObject("SELECT index_status FROM document_chunk WHERE document_version_id=?",String.class,v1));
        assertFalse(docs.detail(id,reader.principal()).versions().stream().filter(v->v.id()==v1).findFirst().orElseThrow().searchable());
        mvc.perform(get("/api/documents/{id}/versions/{v}/file",id,v1).header("Authorization",bearer(reader))).andExpect(status().isOk());
        docs.retire(id,v2,owner.principal()); assertEquals(0,docs.list(reader.principal(),false,1,20).total());
        mvc.perform(get("/api/documents/{id}/versions/{v}/file",id,v2).header("Authorization",bearer(reader))).andExpect(status().isOk());
        docs.delete(id,owner.principal());
        mvc.perform(get("/api/documents/{id}/versions/{v}/file",id,v1).header("Authorization",bearer(owner))).andExpect(status().isNotFound());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM document_version WHERE document_id=?",Integer.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM document_audit WHERE document_id=? AND action='DELETE'",Integer.class,id));
    }
    @Test void duplicatesDatesExpiredVersionsAndIntegrityAreChecked() throws Exception {
        var owner=account("LEGAL",null); var result=create(owner,List.of("LEGAL"),List.of()); long id=result.document().id(), v1=result.versions().getFirst().id();
        mvc.perform(multipart("/api/documents/{id}/versions",id).file(metadata(input("v1"))).file(file("x.pdf",PDF)).header("Authorization",bearer(owner))).andExpect(status().isConflict());
        var time=LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        for(var input:List.of(new DocumentService.VersionInput("future",time.plusDays(1),null),new DocumentService.VersionInput("expired",time.minusDays(2),time.minusDays(1)))) {
            long v=docs.upload(id,input,file("x.pdf",PDF),owner.principal()).versions().getFirst().id(); ready(v);
            mvc.perform(post("/api/documents/{id}/versions/{v}/publish",id,v).header("Authorization",bearer(owner))).andExpect(status().isConflict());
        }
        mvc.perform(multipart("/api/documents/{id}/versions",id).file(metadata(new DocumentService.VersionInput("bad_dates",time,time.minusDays(1)))).file(file("x.pdf",PDF)).header("Authorization",bearer(owner))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/documents").param("page","0").header("Authorization",bearer(owner))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/documents/{id}/versions/{v}/publish",id,v1).header("Authorization",bearer(account("EMPLOYEE",null)))).andExpect(status().isForbidden());
        assertThrows(com.enterprise.compliance.common.BusinessException.class,()->storage.read("../outside"));
        jdbc.update("UPDATE document_version SET content_sha256=? WHERE id=?","0".repeat(64),v1);
        mvc.perform(get("/api/documents/{id}/versions/{v}/file",id,v1).header("Authorization",bearer(owner))).andExpect(status().isServiceUnavailable());
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void transactionRollbackRemovesFileAndDatabaseMetadata() {
        var owner=account("LEGAL",null);
        try {
            String key=new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status->{
                var result=create(owner,List.of("LEGAL"),List.of());
                String stored=jdbc.queryForObject("SELECT storage_key FROM document_version WHERE id=?",String.class,result.versions().getFirst().id());
                assertArrayEquals(PDF,storage.read(stored));
                status.setRollbackOnly(); return stored;
            });
            assertThrows(com.enterprise.compliance.common.BusinessException.class,()->storage.read(key));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM document WHERE owner_id=?",Integer.class,owner.id()));
        } finally {
            jdbc.update("DELETE FROM auth_session WHERE user_id=?",owner.id());
            jdbc.update("DELETE FROM sys_user_role WHERE user_id=?",owner.id());
            jdbc.update("DELETE FROM sys_user WHERE id=?",owner.id());
        }
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void concurrentPublicationHasOneCurrentVersion() throws Exception {
        var owner=account("LEGAL",null); var result=create(owner,List.of("LEGAL"),List.of()); long id=result.document().id(), v=result.versions().getFirst().id(); ready(v);
        var executor=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        try {
            var futures=new ArrayList<Future<?>>();
            for(int i=0;i<2;i++) futures.add(executor.submit(()->{ start.await(); docs.publish(id,v,owner.principal()); return null; }));
            start.countDown(); for(var future:futures) future.get(15,TimeUnit.SECONDS);
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM document_audit WHERE document_id=? AND action='PUBLISH'",Integer.class,id));
            long v2=docs.upload(id,input("v2"),file("v2.pdf",PDF),owner.principal()).versions().getFirst().id(), v3=docs.upload(id,input("v3"),file("v3.pdf",PDF),owner.principal()).versions().getFirst().id(); ready(v2); ready(v3);
            var nextStart=new CountDownLatch(1);
            var second=executor.submit(()->{ nextStart.await(); docs.publish(id,v2,owner.principal()); return null; });
            var third=executor.submit(()->{ nextStart.await(); docs.publish(id,v3,owner.principal()); return null; });
            nextStart.countDown(); second.get(15,TimeUnit.SECONDS); third.get(15,TimeUnit.SECONDS);
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM document_version WHERE document_id=? AND publication_status='PUBLISHED'",Integer.class,id));
            assertEquals(1,docs.detail(id,owner.principal()).versions().stream().filter(DocumentService.Version::searchable).count());
        } finally {
            executor.shutdownNow();
            for(String key:jdbc.queryForList("SELECT storage_key FROM document_version WHERE document_id=?",String.class,id)) storage.remove(key);
            jdbc.update("UPDATE document SET current_version_id=NULL WHERE id=?",id);
            jdbc.update("DELETE FROM document_audit WHERE document_id=?",id);
            jdbc.update("DELETE FROM document_chunk WHERE document_version_id IN (SELECT id FROM document_version WHERE document_id=?)",id);
            jdbc.update("DELETE FROM document_version WHERE document_id=?",id);
            jdbc.update("DELETE FROM document_role_access WHERE document_id=?",id);
            jdbc.update("DELETE FROM document_department_access WHERE document_id=?",id);
            jdbc.update("DELETE FROM document WHERE id=?",id);
            jdbc.update("DELETE FROM auth_session WHERE user_id=?",owner.id());
            jdbc.update("DELETE FROM sys_user_role WHERE user_id=?",owner.id());
            jdbc.update("DELETE FROM sys_user WHERE id=?",owner.id());
        }
    }
}
