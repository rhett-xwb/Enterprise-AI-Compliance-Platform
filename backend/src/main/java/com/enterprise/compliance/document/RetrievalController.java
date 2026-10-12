package com.enterprise.compliance.document;

import com.enterprise.compliance.auth.PlatformPrincipal;
import com.enterprise.compliance.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class RetrievalController {
    public record Query(@NotBlank @Size(max=500) String query,@Min(1) @Max(20) int topK,@DecimalMin("0") @DecimalMax("1") double minScore) {}
    private final IndexStore index;
    private final RetrievalStore store;
    private final RetrievalClient client;
    private final boolean enabled;
    private final boolean asyncEnabled;
    private final com.enterprise.compliance.async.AsyncDocuments async;
    public RetrievalController(IndexStore index,RetrievalStore store,RetrievalClient client,@Value("${app.retrieval.enabled:false}") boolean enabled,
        @Value("${app.async.enabled:false}") boolean asyncEnabled,com.enterprise.compliance.async.AsyncDocuments async) {
        this.index=index; this.store=store; this.client=client; this.enabled=enabled;
        this.asyncEnabled=asyncEnabled; this.async=async;
    }
    private void enabled() { if(!enabled) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"向量检索尚未启用，请联系管理员"); }
    @GetMapping("/documents/{id}/versions/{versionId}/index")
    @PreAuthorize("hasAuthority('document:manage')")
    public ApiResponse<IndexStore.State> state(@PathVariable long id,@PathVariable long versionId,@AuthenticationPrincipal PlatformPrincipal user) {
        return ApiResponse.ok(index.state(id,versionId,user));
    }
    @PostMapping("/documents/{id}/versions/{versionId}/index")
    @PreAuthorize("hasAuthority('document:manage')")
    public ApiResponse<IndexStore.State> index(@PathVariable long id,@PathVariable long versionId,@RequestParam(defaultValue="false") boolean rebuild,@AuthenticationPrincipal PlatformPrincipal user) {
        enabled();
        if(asyncEnabled) return ApiResponse.ok(async.index(id,versionId,user,rebuild));
        var work=index.prepare(id,versionId,user,rebuild);
        if(work!=null) {
            try { index.complete(work,client.call("index",Map.of("documentId",work.documentId(),"documentVersionId",work.documentVersionId(),"generation",work.generation(),"roles",work.roles(),"departmentIds",work.departmentIds(),"chunks",work.chunks()))); }
            catch(Exception ex) { index.fail(work,"INDEX_UNAVAILABLE"); throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"索引未完成，可刷新状态后重试"); }
        }
        return ApiResponse.ok(index.state(id,versionId,user));
    }
    @PostMapping("/retrieval/search")
    @PreAuthorize("hasAuthority('document:read')")
    public ApiResponse<List<RetrievalStore.Hit>> search(@Valid @RequestBody Query query,@AuthenticationPrincipal PlatformPrincipal user) {
        enabled(); var keys=store.keys(user); if(keys.isEmpty()) return ApiResponse.ok(List.of());
        var reply=client.call("search",Map.of("query",query.query().trim(),"keys",keys,"topK",query.topK(),"minScore",query.minScore()));
        var hits=reply.path("hits"); if(!hits.isArray() || hits.size()>query.topK()) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"检索返回无效结果");
        var allowed=new HashSet<>(keys); var scores=new LinkedHashMap<String,Double>();
        for(var hit:hits) {
            String key=hit.path("key").asText(); double score=hit.path("score").asDouble(Double.NaN);
            if(!allowed.contains(key) || !Double.isFinite(score) || score<query.minScore() || score>1.00001 || scores.putIfAbsent(key,score)!=null)
                throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"检索返回无效结果");
        }
        return ApiResponse.ok(store.resolve(user,scores));
    }
}
