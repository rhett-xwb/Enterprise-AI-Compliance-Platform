package com.enterprise.compliance.auth;

import com.enterprise.compliance.user.dto.CreateUserRequest;
import com.enterprise.compliance.user.service.UserService;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class InitialAdminRunner implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final UserService users;
    private final Validator validator;
    private final boolean enabled;
    private final String username;
    private final String password;

    public InitialAdminRunner(JdbcTemplate jdbc, UserService users, Validator validator,
            @Value("${app.initial-admin.enabled}") boolean enabled,
            @Value("${app.initial-admin.username}") String username,
            @Value("${app.initial-admin.password}") String password) {
        this.jdbc=jdbc; this.users=users; this.validator=validator;
        this.enabled=enabled; this.username=username; this.password=password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled || jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Long.class) != 0) return;
        var request = new CreateUserRequest(username, password, "系统管理员", null, null);
        if (!validator.validate(request).isEmpty()) throw new IllegalStateException("Configure valid initial administrator credentials");
        long id = users.create(request).id();
        jdbc.update("DELETE FROM sys_user_role WHERE user_id=?", id);
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT ?,id FROM sys_role WHERE code='ADMIN'", id);
    }
}
