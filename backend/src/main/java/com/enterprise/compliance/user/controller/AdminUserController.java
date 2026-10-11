package com.enterprise.compliance.user.controller;

import com.enterprise.compliance.common.ApiResponse;
import com.enterprise.compliance.user.dto.*;
import com.enterprise.compliance.user.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN') and hasAuthority('user:manage')")
public class AdminUserController {
    private final UserService users;
    private final JdbcTemplate jdbc;
    public AdminUserController(UserService users, JdbcTemplate jdbc) { this.users=users; this.jdbc=jdbc; }

    @GetMapping
    ApiResponse<PageResponse<UserResponse>> list(@RequestParam(defaultValue="1") @Min(1) @Max(1000000) long page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) long size) { return ApiResponse.ok(users.list(page,size)); }
    @GetMapping("/{id}")
    ApiResponse<UserResponse> get(@PathVariable @Positive long id) { return ApiResponse.ok(users.get(id)); }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<UserResponse> create(@Valid @RequestBody CreateUserRequest request) { return ApiResponse.ok(users.create(request)); }
    @PutMapping("/{id}")
    ApiResponse<UserResponse> update(@PathVariable @Positive long id, @Valid @RequestBody UpdateUserRequest request) { return ApiResponse.ok(users.update(id,request)); }
    @DeleteMapping("/{id}")
    ApiResponse<Void> delete(@PathVariable @Positive long id) { users.delete(id); return ApiResponse.ok(null); }

    public record RolesRequest(@NotEmpty Set<@Pattern(regexp="ADMIN|LEGAL|EMPLOYEE") String> roles) {}

    @PutMapping("/{id}/roles")
    @Transactional
    ApiResponse<Set<String>> roles(@PathVariable @Positive long id, @Valid @RequestBody RolesRequest request) {
        users.get(id);
        if (!request.roles().contains("ADMIN")) users.ensureAdminRemains(id);
        else jdbc.queryForObject("SELECT id FROM sys_role WHERE code='ADMIN' FOR UPDATE", Long.class);
        jdbc.update("DELETE FROM sys_user_role WHERE user_id=?",id);
        for (String code : request.roles()) {
            jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT ?,id FROM sys_role WHERE code=?",id,code);
        }
        return ApiResponse.ok(request.roles());
    }
}
