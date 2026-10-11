package com.enterprise.compliance.access;

import com.enterprise.compliance.auth.PlatformPrincipal;
import com.enterprise.compliance.common.ApiResponse;
import com.enterprise.compliance.common.BusinessException;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class ScopedResourceController {
    private final JdbcTemplate jdbc;
    public ScopedResourceController(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public record Department(long id, String code, String name) {}
    public record ApprovalRequest(long id, long applicantId, String requestType, String title, String status) {}

    @GetMapping("/api/departments/{id}")
    @PreAuthorize("@resourceAccess.canReadDepartment(authentication, #id)")
    ApiResponse<Department> department(@PathVariable @Positive long id) {
        var rows = jdbc.query("SELECT id,code,name FROM sys_department WHERE id=?",
                (rs,n) -> new Department(rs.getLong("id"),rs.getString("code"),rs.getString("name")),id);
        if (rows.isEmpty()) throw missing();
        return ApiResponse.ok(rows.getFirst());
    }

    @GetMapping("/api/approval-requests/{id}")
    @PreAuthorize("hasAuthority('approval:submit') or hasAuthority('approval:review')")
    ApiResponse<ApprovalRequest> request(@PathVariable @Positive long id, @AuthenticationPrincipal PlatformPrincipal user) {
        // Scope in SQL: no unfiltered fetch and no disclosure that another user's request exists.
        var rows = jdbc.query("""
                SELECT r.id,r.applicant_id,r.request_type,r.title,r.status FROM approval_request r
                WHERE r.id=? AND (r.applicant_id=? OR EXISTS (
                  SELECT 1 FROM workflow_instance i JOIN approval_task t ON t.instance_id=i.id
                  WHERE i.request_id=r.id AND t.assignee_id=? AND t.status='PENDING'
                ))
                """, (rs,n) -> new ApprovalRequest(rs.getLong("id"),rs.getLong("applicant_id"),
                        rs.getString("request_type"),rs.getString("title"),rs.getString("status")),id,user.id(),user.id());
        if (rows.isEmpty()) throw missing();
        return ApiResponse.ok(rows.getFirst());
    }

    private BusinessException missing() { return new BusinessException(HttpStatus.NOT_FOUND,"Resource not found"); }
}
