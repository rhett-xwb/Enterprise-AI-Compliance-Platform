-- A single publication lock serializes version allocation and submission selection.
CREATE TABLE workflow_publication (
    code VARCHAR(64) PRIMARY KEY,
    active_definition_id BIGINT NULL,
    CONSTRAINT fk_publication_definition FOREIGN KEY (active_definition_id) REFERENCES workflow_definition(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO workflow_publication(code) VALUES ('TRAVEL');
ALTER TABLE request_submission DROP INDEX request_id,
    ADD CONSTRAINT uk_submission_content UNIQUE(request_id,content_version);
ALTER TABLE workflow_instance ADD COLUMN route_json JSON NULL,
    ADD CONSTRAINT uk_instance_content UNIQUE(request_id,request_content_version);
ALTER TABLE approval_task ADD COLUMN node_index INT NOT NULL DEFAULT 0,
    ADD CONSTRAINT uk_task_node UNIQUE(instance_id,node_index);
