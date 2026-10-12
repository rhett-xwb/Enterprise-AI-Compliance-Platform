ALTER TABLE document_version DROP CHECK ck_version_processing;
ALTER TABLE document_version ADD CONSTRAINT ck_version_processing
    CHECK (processing_status IN ('PENDING','PROCESSING','PARSED','READY','FAILED'));
ALTER TABLE document_processing_task ADD COLUMN run_token CHAR(36) NULL,
    ADD COLUMN lease_until DATETIME(6) NULL,
    ADD COLUMN parser_version VARCHAR(100) NULL;
CREATE TABLE document_parse_source (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_version_id BIGINT NOT NULL,
    source_index INT NOT NULL,
    page_number INT NULL,
    location VARCHAR(255) NOT NULL,
    section_title VARCHAR(255) NULL,
    text_content MEDIUMTEXT NOT NULL,
    extraction_method VARCHAR(16) NOT NULL,
    confidence DOUBLE NULL,
    warnings_json JSON NOT NULL,
    CONSTRAINT uk_parse_source UNIQUE(document_version_id, source_index),
    CONSTRAINT fk_parse_source_version FOREIGN KEY(document_version_id) REFERENCES document_version(id),
    CONSTRAINT ck_parse_source_index CHECK(source_index >= 0),
    CONSTRAINT ck_parse_source_page CHECK(page_number IS NULL OR page_number > 0),
    CONSTRAINT ck_parse_source_confidence CHECK(confidence IS NULL OR (confidence >= 0 AND confidence <= 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
