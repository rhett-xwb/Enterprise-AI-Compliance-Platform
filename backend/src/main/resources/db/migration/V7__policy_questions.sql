CREATE TABLE policy_question (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    question VARCHAR(500) NOT NULL,
    status VARCHAR(32) NOT NULL,
    answer TEXT NOT NULL,
    evidence_json JSON NOT NULL,
    citation_ids_json JSON NOT NULL,
    model VARCHAR(255) NOT NULL,
    embedding_model VARCHAR(255) NOT NULL,
    elapsed_ms BIGINT NOT NULL,
    error_code VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_policy_question_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT ck_policy_question_status CHECK (status IN ('ANSWERED','INSUFFICIENT','CONFLICT','FAILED')),
    INDEX ix_policy_question_user (user_id, id)
);
