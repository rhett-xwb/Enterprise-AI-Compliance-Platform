CREATE TABLE auth_session (
    session_id CHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at TIMESTAMP(6) NOT NULL,
    revoked_at TIMESTAMP(6) NULL,
    CONSTRAINT fk_auth_session_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    INDEX ix_auth_session_user (user_id, revoked_at, expires_at),
    INDEX ix_auth_session_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
