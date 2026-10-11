package com.enterprise.compliance.user.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.compliance.common.BusinessException;
import com.enterprise.compliance.user.dto.CreateUserRequest;
import com.enterprise.compliance.user.dto.PageResponse;
import com.enterprise.compliance.user.dto.UpdateUserRequest;
import com.enterprise.compliance.user.dto.UserResponse;
import com.enterprise.compliance.user.entity.UserEntity;
import com.enterprise.compliance.user.mapper.UserMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    private final UserMapper mapper;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public UserService(UserMapper mapper, JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    public PageResponse<UserResponse> list(long page, long size) {
        var result = mapper.selectPage(new Page<UserEntity>(page, size),
                new QueryWrapper<UserEntity>().isNull("deleted_at").orderByDesc("id"));
        return new PageResponse<>(result.getCurrent(), result.getSize(), result.getTotal(),
                result.getRecords().stream().map(UserResponse::from).toList());
    }

    public UserResponse get(long id) { return UserResponse.from(find(id)); }

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Password exceeds 72 UTF-8 bytes");
        }
        validateDepartment(request.departmentId());
        var user = new UserEntity();
        user.setUsername(request.username());
        user.setPasswordHash(encoder.encode(request.password()));
        user.setDisplayName(request.displayName());
        user.setEmail(request.email());
        user.setDepartmentId(request.departmentId());
        user.setEnabled(true);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(user.getCreatedAt());
        mapper.insert(user);
        jdbc.update("INSERT INTO sys_user_role(user_id, role_id) SELECT ?, id FROM sys_role WHERE code = 'EMPLOYEE'", user.getId());
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse update(long id, UpdateUserRequest request) {
        find(id);
        validateDepartment(request.departmentId());
        // Explicit null writes let callers clear optional fields; deleted rows cannot be revived.
        int count = mapper.update(null, new UpdateWrapper<UserEntity>()
                .eq("id", id).isNull("deleted_at")
                .set("display_name", request.displayName()).set("email", request.email())
                .set("department_id", request.departmentId()).set("enabled", request.enabled())
                .set("updated_at", LocalDateTime.now()));
        if (count == 0) throw missing();
        return get(id);
    }

    @Transactional
    public void delete(long id) {
        LocalDateTime now = LocalDateTime.now();
        int count = mapper.update(null, new UpdateWrapper<UserEntity>()
                .eq("id", id).isNull("deleted_at").set("enabled", false)
                .set("deleted_at", now).set("updated_at", now));
        if (count == 0) throw missing();
        jdbc.update("DELETE FROM sys_user_role WHERE user_id=?", id);
    }

    private UserEntity find(long id) {
        UserEntity user = mapper.selectById(id);
        if (user == null || user.getDeletedAt() != null) throw missing();
        return user;
    }

    private void validateDepartment(Long id) {
        if (id != null && jdbc.queryForObject("SELECT COUNT(*) FROM sys_department WHERE id=?", Long.class, id) == 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Department does not exist");
        }
    }

    private BusinessException missing() {
        return new BusinessException(HttpStatus.NOT_FOUND, "User not found");
    }
}
