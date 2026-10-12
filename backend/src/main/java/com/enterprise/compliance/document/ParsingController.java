package com.enterprise.compliance.document;

import com.enterprise.compliance.auth.PlatformPrincipal;
import com.enterprise.compliance.common.ApiResponse;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.enterprise.compliance.common.BusinessException;

@RestController
@RequestMapping("/api/documents/{id}/versions/{versionId}")
@PreAuthorize("hasAuthority('document:manage')")
public class ParsingController {
    private final ParsingStore store;
    private final boolean enabled;
    private final boolean asyncEnabled;
    private final com.enterprise.compliance.async.AsyncDocuments async;
    public ParsingController(ParsingStore store,@Value("${app.parser.enabled:false}") boolean enabled,
        @Value("${app.async.enabled:false}") boolean asyncEnabled,com.enterprise.compliance.async.AsyncDocuments async) { this.store=store; this.enabled=enabled; this.asyncEnabled=asyncEnabled; this.async=async; }
    @PostMapping("/parse")
    ResponseEntity<ApiResponse<ParsingStore.Task>> parse(@PathVariable @Positive long id,@PathVariable @Positive long versionId,
            @RequestParam(defaultValue="false") boolean retry,@AuthenticationPrincipal PlatformPrincipal user) {
        if(!enabled) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"解析服务尚未启用，请联系管理员");
        return ResponseEntity.accepted().body(ApiResponse.ok(asyncEnabled ? async.parse(id,versionId,user,retry) : store.enqueue(id,versionId,user,retry)));
    }
    @GetMapping("/parse")
    ApiResponse<ParsingStore.Task> status(@PathVariable @Positive long id,@PathVariable @Positive long versionId,@AuthenticationPrincipal PlatformPrincipal user) {
        return ApiResponse.ok(store.status(id,versionId,user));
    }
    @GetMapping("/sources")
    ApiResponse<List<ParsingStore.Source>> sources(@PathVariable @Positive long id,@PathVariable @Positive long versionId,@AuthenticationPrincipal PlatformPrincipal user) {
        return ApiResponse.ok(store.sources(id,versionId,user));
    }
}
