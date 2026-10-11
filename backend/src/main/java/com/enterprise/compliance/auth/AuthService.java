package com.enterprise.compliance.auth;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.enterprise.compliance.common.BusinessException;
import com.enterprise.compliance.user.entity.UserEntity;
import com.enterprise.compliance.user.mapper.UserMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService implements Converter<Jwt, AbstractAuthenticationToken> {
    private final UserMapper users;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final Clock clock;
    private final String issuer;
    private final long ttl;
    private final String dummyHash;

    public AuthService(UserMapper users, JdbcTemplate jdbc, PasswordEncoder passwords,
            JwtEncoder encoder, Clock clock, @Value("${app.jwt.issuer}") String issuer,
            @Value("${app.jwt.ttl-seconds}") long ttl) {
        if (ttl < 60 || ttl > 3600) throw new IllegalStateException("JWT TTL must be 60-3600 seconds");
        this.users = users; this.jdbc = jdbc; this.passwords = passwords;
        this.encoder = encoder; this.clock = clock; this.issuer = issuer; this.ttl = ttl;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public record LoginResult(String accessToken, String tokenType, long expiresIn) {
        @Override public String toString() { return "LoginResult[redacted]"; }
    }

    @Transactional
    public LoginResult login(String username, String password) {
        if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
        // Lock the user row so deletion/disable cannot race creation of a new session.
        UserEntity user = users.selectOne(new QueryWrapper<UserEntity>().eq("username", username).last("FOR UPDATE"));
        boolean matches = passwords.matches(password, user == null ? dummyHash : user.getPasswordHash());
        if (!matches || user == null || !Boolean.TRUE.equals(user.getEnabled()) || user.getDeletedAt() != null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expires = now.plusSeconds(ttl);
        String sessionId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO auth_session(session_id,user_id,expires_at) VALUES(?,?,?)", sessionId, user.getId(), Timestamp.from(expires));
        var claims = JwtClaimsSet.builder().issuer(issuer).subject(user.getId().toString())
                .issuedAt(now).expiresAt(expires).claim("jti", sessionId).build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new LoginResult(token, "Bearer", ttl);
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt token) {
        long id;
        try { id = Long.parseLong(token.getSubject()); }
        catch (NumberFormatException ex) { throw invalid(); }
        Integer sessions = jdbc.queryForObject("SELECT COUNT(*) FROM auth_session WHERE session_id=? AND user_id=? AND revoked_at IS NULL AND expires_at>?", Integer.class,
                token.getId(), id, Timestamp.from(clock.instant()));
        UserEntity user = users.selectById(id);
        if (sessions == null || sessions == 0 || user == null || user.getDeletedAt() != null || !Boolean.TRUE.equals(user.getEnabled())) throw invalid();
        List<String> roles = jdbc.queryForList("SELECT r.code FROM sys_role r JOIN sys_user_role ur ON r.id=ur.role_id WHERE ur.user_id=? ORDER BY r.code", String.class, id);
        List<String> permissions = jdbc.queryForList("SELECT DISTINCT p.code FROM sys_permission p JOIN sys_role_permission rp ON p.id=rp.permission_id JOIN sys_user_role ur ON rp.role_id=ur.role_id WHERE ur.user_id=? ORDER BY p.code", String.class, id);
        var authorities = new java.util.ArrayList<SimpleGrantedAuthority>();
        roles.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
        permissions.forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission)));
        var principal = new PlatformPrincipal(id, user.getUsername(), user.getDepartmentId(), roles, permissions, token.getId());
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
    }

    @Transactional
    public void logout(PlatformPrincipal user) {
        jdbc.update("UPDATE auth_session SET revoked_at=? WHERE session_id=? AND user_id=? AND revoked_at IS NULL", Timestamp.from(clock.instant()), user.sessionId(), user.id());
    }

    private OAuth2AuthenticationException invalid() {
        return new OAuth2AuthenticationException(new OAuth2Error("invalid_token", "Token is no longer active", null));
    }
}
