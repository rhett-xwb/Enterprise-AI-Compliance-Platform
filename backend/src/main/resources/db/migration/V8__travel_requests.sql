ALTER TABLE approval_attachment ADD COLUMN content_type VARCHAR(128) NOT NULL DEFAULT 'application/octet-stream',
    ADD COLUMN deleted_at DATETIME(6) NULL;
CREATE TABLE request_submission (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id BIGINT NOT NULL UNIQUE,
    content_version INT NOT NULL,
    snapshot_json JSON NOT NULL,
    analysis_id BIGINT NOT NULL,
    submitted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_submission_request FOREIGN KEY (request_id) REFERENCES approval_request(id),
    CONSTRAINT fk_submission_analysis FOREIGN KEY (analysis_id) REFERENCES ai_analysis_record(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
