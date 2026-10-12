package com.enterprise.compliance.async;

import com.enterprise.compliance.auth.PlatformPrincipal;
import com.enterprise.compliance.common.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/document-tasks")
@PreAuthorize("hasRole('ADMIN')")
public class TaskController {
    private final AsyncJobs jobs; private final SourceCache cache; private final boolean enabled; private final JdbcTemplate jdbc;
    public TaskController(AsyncJobs jobs,SourceCache cache,@Value("${app.async.enabled:false}") boolean enabled,JdbcTemplate jdbc) { this.jobs=jobs; this.cache=cache; this.enabled=enabled; this.jdbc=jdbc; }
    @GetMapping public ApiResponse<?> list() { return ApiResponse.ok(jobs.list()); }
    @GetMapping("/metrics") public ApiResponse<?> metrics() { return ApiResponse.ok(java.util.Map.of("asyncEnabled",enabled,"jobs",jobs.metrics(),"cache",cache.metrics())); }
    @PostMapping("/{id}/retry") @Transactional
    public ApiResponse<?> retry(@PathVariable long id,@AuthenticationPrincipal PlatformPrincipal actor) {
        if(!enabled) throw new BusinessException(HttpStatus.CONFLICT,"请先启用异步任务服务");
        jobs.retry(id);
        jdbc.update("INSERT INTO document_audit(document_id,version_id,actor_id,action,detail_json) SELECT document_id,document_version_id,?,'ASYNC_RETRY',JSON_OBJECT('jobId',id) FROM document_async_job WHERE id=?",actor.id(),id);
        return ApiResponse.ok("已重新排队");
    }
}
