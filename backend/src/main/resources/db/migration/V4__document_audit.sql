ALTER TABLE document_version ADD COLUMN published_at DATETIME(6) NULL;
CREATE TABLE document_audit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_id BIGINT NOT NULL,
    version_id BIGINT NULL,
    actor_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    detail_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_document_audit_document FOREIGN KEY (document_id) REFERENCES document(id),
    CONSTRAINT fk_document_audit_version FOREIGN KEY (document_id, version_id) REFERENCES document_version(document_id, id),
    CONSTRAINT fk_document_audit_actor FOREIGN KEY (actor_id) REFERENCES sys_user(id),
    INDEX ix_document_audit_history (document_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
