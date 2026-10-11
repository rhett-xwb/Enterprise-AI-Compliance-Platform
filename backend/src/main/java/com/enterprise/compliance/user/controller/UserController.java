package com.enterprise.compliance.user.controller;

import com.enterprise.compliance.common.ApiResponse;
import com.enterprise.compliance.user.dto.CreateUserRequest;
import com.enterprise.compliance.user.dto.PageResponse;
import com.enterprise.compliance.user.dto.UpdateUserRequest;
import com.enterprise.compliance.user.dto.UserResponse;
import com.enterprise.compliance.user.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/bootstrap/users")
public class UserController {
    private final UserService service;

    public UserController(UserService service) { this.service = service; }

    @GetMapping
    ApiResponse<PageResponse<UserResponse>> list(
            @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size) {
        return ApiResponse.ok(service.list(page, size));
    }

    @GetMapping("/{id}")
    ApiResponse<UserResponse> get(@PathVariable @Positive long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{id}")
    ApiResponse<UserResponse> update(@PathVariable @Positive long id,
            @Valid @RequestBody UpdateUserRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    ApiResponse<Void> delete(@PathVariable @Positive long id) {
        service.delete(id);
        return ApiResponse.ok(null);
    }
}
