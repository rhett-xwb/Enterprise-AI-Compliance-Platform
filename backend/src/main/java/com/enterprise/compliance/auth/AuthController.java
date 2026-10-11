package com.enterprise.compliance.auth;

import com.enterprise.compliance.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService service;
    public AuthController(AuthService service) { this.service = service; }

    public record LoginRequest(@NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 72) String password) {
        @Override public String toString() { return "LoginRequest[redacted]"; }
    }

    public record CurrentUser(long id, String username, Long departmentId,
            java.util.List<String> roles, java.util.List<String> permissions) {}

    @PostMapping("/login")
    ApiResponse<AuthService.LoginResult> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(service.login(request.username(), request.password()));
    }

    @GetMapping("/me")
    ApiResponse<CurrentUser> me(@AuthenticationPrincipal PlatformPrincipal user) {
        return ApiResponse.ok(new CurrentUser(user.id(), user.username(), user.departmentId(), user.roles(), user.permissions()));
    }

    @PostMapping("/logout")
    ApiResponse<Void> logout(@AuthenticationPrincipal PlatformPrincipal user) {
        service.logout(user);
        return ApiResponse.ok(null);
    }
}
