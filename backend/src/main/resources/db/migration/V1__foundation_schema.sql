-- MySQL 8.0+ foundation. Flyway owns schema history; no DROP or demo passwords.
CREATE TABLE sys_department (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    parent_id BIGINT NULL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(100) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_department_code UNIQUE (code),
    CONSTRAINT fk_department_parent FOREIGN KEY (parent_id) REFERENCES sys_department(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sys_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    email VARCHAR(254) NULL,
    department_id BIGINT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL,
    CONSTRAINT uk_user_username UNIQUE (username),
    CONSTRAINT fk_user_department FOREIGN KEY (department_id) REFERENCES sys_department(id),
    INDEX ix_user_visible (deleted_at, id),
    INDEX ix_user_department (department_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sys_role (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(100) NOT NULL,
    CONSTRAINT uk_role_code UNIQUE (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sys_permission (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(100) NOT NULL,
    name VARCHAR(100) NOT NULL,
    CONSTRAINT uk_permission_code UNIQUE (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sys_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES sys_role(id),
    INDEX ix_user_role_role (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sys_role_permission (
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES sys_role(id),
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES sys_permission(id),
    INDEX ix_role_permission_permission (permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE document (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(255) NOT NULL,
    knowledge_domain VARCHAR(32) NOT NULL,
    owner_id BIGINT NOT NULL,
    current_version_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL,
    CONSTRAINT fk_document_owner FOREIGN KEY (owner_id) REFERENCES sys_user(id),
    CONSTRAINT ck_document_domain CHECK (knowledge_domain IN ('POLICY','LAW','TEMPLATE','CONTRACT')),
    INDEX ix_document_domain (knowledge_domain, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE document_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_id BIGINT NOT NULL,
    version_no VARCHAR(32) NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    publication_status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    processing_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    effective_at DATETIME(6) NULL,
    expires_at DATETIME(6) NULL,
    creator_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_document_version UNIQUE (document_id, version_no),
    CONSTRAINT uk_document_version_identity UNIQUE (document_id, id),
    CONSTRAINT fk_version_document FOREIGN KEY (document_id) REFERENCES document(id),
    CONSTRAINT fk_version_creator FOREIGN KEY (creator_id) REFERENCES sys_user(id),
    CONSTRAINT ck_version_size CHECK (size_bytes >= 0),
    CONSTRAINT ck_version_publication CHECK (publication_status IN ('DRAFT','PUBLISHED','RETIRED')),
    CONSTRAINT ck_version_processing CHECK (processing_status IN ('PENDING','PROCESSING','READY','FAILED')),
    CONSTRAINT ck_version_dates CHECK (expires_at IS NULL OR (effective_at IS NOT NULL AND expires_at > effective_at)),
    INDEX ix_version_effective (publication_status, effective_at, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Composite reference prevents a document from pointing at another document's version.
ALTER TABLE document ADD CONSTRAINT fk_document_current_version
    FOREIGN KEY (id, current_version_id) REFERENCES document_version(document_id, id);

CREATE TABLE document_role_access (
    document_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (document_id, role_id),
    CONSTRAINT fk_document_access_document FOREIGN KEY (document_id) REFERENCES document(id),
    CONSTRAINT fk_document_access_role FOREIGN KEY (role_id) REFERENCES sys_role(id),
    INDEX ix_document_access_role (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE document_department_access (
    document_id BIGINT NOT NULL,
    department_id BIGINT NOT NULL,
    PRIMARY KEY (document_id, department_id),
    CONSTRAINT fk_document_dept_document FOREIGN KEY (document_id) REFERENCES document(id),
    CONSTRAINT fk_document_dept_department FOREIGN KEY (department_id) REFERENCES sys_department(id),
    INDEX ix_document_dept_department (department_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE document_processing_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_version_id BIGINT NOT NULL,
    task_key VARCHAR(100) NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    error_code VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_processing_task_key UNIQUE (task_key),
    CONSTRAINT fk_processing_task_version FOREIGN KEY (document_version_id) REFERENCES document_version(id),
    CONSTRAINT ck_processing_attempts CHECK (attempts >= 0),
    INDEX ix_processing_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE document_chunk (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_version_id BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    page_number INT NULL,
    section_title VARCHAR(255) NULL,
    text_content MEDIUMTEXT NOT NULL,
    embedding_model VARCHAR(100) NULL,
    vector_key VARCHAR(255) NULL,
    index_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    CONSTRAINT uk_chunk_order UNIQUE (document_version_id, chunk_index),
    CONSTRAINT fk_chunk_version FOREIGN KEY (document_version_id) REFERENCES document_version(id),
    CONSTRAINT ck_chunk_page CHECK (page_number IS NULL OR page_number > 0),
    CONSTRAINT ck_chunk_index CHECK (chunk_index >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Business-level workflow metadata; not a replacement for Flowable engine tables.
CREATE TABLE workflow_definition (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    version_no INT NOT NULL,
    name VARCHAR(100) NOT NULL,
    config_json JSON NOT NULL,
    engine_definition_key VARCHAR(255) NULL,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    creator_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_workflow_version UNIQUE (code, version_no),
    CONSTRAINT fk_workflow_creator FOREIGN KEY (creator_id) REFERENCES sys_user(id),
    CONSTRAINT ck_workflow_version CHECK (version_no > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE approval_request (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    applicant_id BIGINT NOT NULL,
    request_type VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    amount DECIMAL(18,2) NULL,
    currency CHAR(3) NULL,
    content_json JSON NOT NULL,
    content_version INT NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_request_applicant FOREIGN KEY (applicant_id) REFERENCES sys_user(id),
    CONSTRAINT ck_request_amount CHECK (amount IS NULL OR amount >= 0),
    CONSTRAINT ck_request_version CHECK (content_version > 0),
    INDEX ix_request_applicant (applicant_id, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE approval_attachment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id BIGINT NOT NULL,
    content_version INT NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    CONSTRAINT fk_attachment_request FOREIGN KEY (request_id) REFERENCES approval_request(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE workflow_instance (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id BIGINT NOT NULL,
    definition_id BIGINT NOT NULL,
    request_content_version INT NOT NULL,
    request_snapshot JSON NOT NULL,
    engine_instance_key VARCHAR(255) NULL,
    status VARCHAR(32) NOT NULL,
    started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ended_at DATETIME(6) NULL,
    CONSTRAINT fk_instance_request FOREIGN KEY (request_id) REFERENCES approval_request(id),
    CONSTRAINT fk_instance_definition FOREIGN KEY (definition_id) REFERENCES workflow_definition(id),
    INDEX ix_instance_request (request_id, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE approval_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    instance_id BIGINT NOT NULL,
    node_key VARCHAR(100) NOT NULL,
    assignee_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    lock_version INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6) NULL,
    CONSTRAINT fk_approval_task_instance FOREIGN KEY (instance_id) REFERENCES workflow_instance(id),
    CONSTRAINT fk_approval_task_assignee FOREIGN KEY (assignee_id) REFERENCES sys_user(id),
    INDEX ix_approval_task_inbox (assignee_id, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE approval_action (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id BIGINT NOT NULL,
    task_id BIGINT NULL,
    actor_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    before_status VARCHAR(32) NOT NULL,
    after_status VARCHAR(32) NOT NULL,
    opinion TEXT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_action_request FOREIGN KEY (request_id) REFERENCES approval_request(id),
    CONSTRAINT fk_action_task FOREIGN KEY (task_id) REFERENCES approval_task(id),
    CONSTRAINT fk_action_actor FOREIGN KEY (actor_id) REFERENCES sys_user(id),
    INDEX ix_action_request (request_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_analysis_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id BIGINT NULL,
    document_version_id BIGINT NULL,
    request_content_version INT NULL,
    status VARCHAR(32) NOT NULL,
    risk_level VARCHAR(32) NULL,
    result_json JSON NULL,
    model_name VARCHAR(100) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_analysis_request FOREIGN KEY (request_id) REFERENCES approval_request(id),
    CONSTRAINT fk_analysis_version FOREIGN KEY (document_version_id) REFERENCES document_version(id),
    CONSTRAINT ck_analysis_target CHECK ((request_id IS NOT NULL AND document_version_id IS NULL AND request_content_version IS NOT NULL) OR (request_id IS NULL AND document_version_id IS NOT NULL)),
    INDEX ix_analysis_request (request_id, request_content_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_chat_session (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(255) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_chat_session_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    INDEX ix_chat_user (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_chat_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    speaker VARCHAR(16) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    model_name VARCHAR(100) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_chat_message_session FOREIGN KEY (session_id) REFERENCES ai_chat_session(id),
    CONSTRAINT ck_chat_speaker CHECK (speaker IN ('USER','ASSISTANT')),
    INDEX ix_chat_message_session (session_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_citation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    message_id BIGINT NULL,
    analysis_id BIGINT NULL,
    chunk_id BIGINT NOT NULL,
    quoted_text TEXT NOT NULL,
    CONSTRAINT fk_citation_message FOREIGN KEY (message_id) REFERENCES ai_chat_message(id),
    CONSTRAINT fk_citation_analysis FOREIGN KEY (analysis_id) REFERENCES ai_analysis_record(id),
    CONSTRAINT fk_citation_chunk FOREIGN KEY (chunk_id) REFERENCES document_chunk(id),
    CONSTRAINT ck_citation_owner CHECK ((message_id IS NOT NULL AND analysis_id IS NULL) OR (message_id IS NULL AND analysis_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
