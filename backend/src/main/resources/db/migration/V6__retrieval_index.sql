ALTER TABLE document_chunk ADD COLUMN source_index INT NULL,
    ADD COLUMN source_location VARCHAR(255) NULL,
    ADD COLUMN start_offset INT NULL,
    ADD COLUMN end_offset INT NULL;
ALTER TABLE document_version ADD COLUMN index_generation CHAR(36) NULL;
CREATE TABLE document_index_run (
    document_version_id BIGINT PRIMARY KEY,
    generation CHAR(36) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 1,
    error_code VARCHAR(64) NULL,
    lease_until DATETIME(6) NULL,
    embedding_model VARCHAR(100) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_index_run_version FOREIGN KEY(document_version_id) REFERENCES document_version(id),
    CONSTRAINT ck_index_run_status CHECK(status IN ('PROCESSING','SUCCEEDED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
