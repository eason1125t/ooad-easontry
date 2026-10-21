-- Functional RBAC0 schema. See docs/数据库设计.md and the accompanying ER diagram.
-- Only versioned migrations change business structures; source XLSX files remain read-only.
CREATE TABLE source_batch (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    kind VARCHAR(24) NOT NULL,
    file_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    file_name VARCHAR(200) NOT NULL,
    record_count INT NOT NULL,
    imported_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_batch_source UNIQUE (kind, file_sha256),
    CONSTRAINT ck_batch_count CHECK (record_count >= 0)
) ENGINE=InnoDB;

CREATE TABLE organization (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_key VARCHAR(180) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    parent_id BIGINT NULL,
    source_batch_id BIGINT NULL,
    source_row INT NULL,
    CONSTRAINT fk_org_parent FOREIGN KEY (parent_id) REFERENCES organization(id),
    CONSTRAINT fk_org_batch FOREIGN KEY (source_batch_id) REFERENCES source_batch(id),
    CONSTRAINT ck_org_self CHECK (parent_id IS NULL OR parent_id <> id),
    CONSTRAINT ck_org_name CHECK (CHAR_LENGTH(TRIM(name)) > 0)
) ENGINE=InnoDB;

CREATE TABLE employee (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_key VARCHAR(180) NOT NULL UNIQUE,
    full_name VARCHAR(100) NOT NULL,
    organization_id BIGINT NOT NULL,
    position_name VARCHAR(100) NOT NULL,
    phone VARCHAR(40) NULL,
    synthetic BOOLEAN NOT NULL DEFAULT FALSE,
    source_batch_id BIGINT NULL,
    source_row INT NULL,
    CONSTRAINT fk_employee_org FOREIGN KEY (organization_id) REFERENCES organization(id),
    CONSTRAINT fk_employee_batch FOREIGN KEY (source_batch_id) REFERENCES source_batch(id)
) ENGINE=InnoDB;

CREATE TABLE account (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    display_name VARCHAR(80) NOT NULL,
    employee_id BIGINT NULL UNIQUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    locked BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    grants_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_account_employee FOREIGN KEY (employee_id) REFERENCES employee(id),
    CONSTRAINT ck_account_enabled CHECK (enabled IN (0, 1)),
    CONSTRAINT ck_account_locked CHECK (locked IN (0, 1)),
    CONSTRAINT ck_account_name CHECK (CHAR_LENGTH(TRIM(display_name)) > 0)
) ENGINE=InnoDB;

CREATE TABLE role (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    name VARCHAR(80) NOT NULL,
    description VARCHAR(300) NOT NULL DEFAULT '',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    grants_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_role_enabled CHECK (enabled IN (0, 1)),
    CONSTRAINT ck_role_name CHECK (CHAR_LENGTH(TRIM(name)) > 0)
) ENGINE=InnoDB;

CREATE TABLE resource (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    parent_id BIGINT NULL,
    code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    source_batch_id BIGINT NULL,
    source_row INT NULL,
    CONSTRAINT fk_resource_parent FOREIGN KEY (parent_id) REFERENCES resource(id),
    CONSTRAINT fk_resource_batch FOREIGN KEY (source_batch_id) REFERENCES source_batch(id),
    CONSTRAINT ck_resource_kind CHECK (kind IN ('APPLICATION', 'MODULE', 'FEATURE')),
    CONSTRAINT ck_resource_root CHECK ((kind = 'APPLICATION' AND parent_id IS NULL) OR (kind <> 'APPLICATION' AND parent_id IS NOT NULL)),
    CONSTRAINT ck_resource_self CHECK (parent_id IS NULL OR parent_id <> id),
    CONSTRAINT ck_resource_enabled CHECK (enabled IN (0, 1))
) ENGINE=InnoDB;

CREATE TABLE permission (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    resource_id BIGINT NOT NULL,
    action VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    code VARCHAR(150) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_permission_resource FOREIGN KEY (resource_id) REFERENCES resource(id),
    CONSTRAINT uk_permission_action UNIQUE (resource_id, action),
    CONSTRAINT ck_permission_enabled CHECK (enabled IN (0, 1))
) ENGINE=InnoDB;

CREATE TABLE user_role (
    account_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (account_id, role_id),
    KEY ix_user_role_role (role_id, account_id),
    CONSTRAINT fk_ur_account FOREIGN KEY (account_id) REFERENCES account(id),
    CONSTRAINT fk_ur_role FOREIGN KEY (role_id) REFERENCES role(id)
) ENGINE=InnoDB;

CREATE TABLE role_permission (
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    KEY ix_role_permission_permission (permission_id, role_id),
    CONSTRAINT fk_rp_role FOREIGN KEY (role_id) REFERENCES role(id),
    CONSTRAINT fk_rp_permission FOREIGN KEY (permission_id) REFERENCES permission(id)
) ENGINE=InnoDB;

CREATE TABLE credential (
    account_id BIGINT PRIMARY KEY,
    password_hash VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    changed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_credential_account FOREIGN KEY (account_id) REFERENCES account(id)
) ENGINE=InnoDB;

CREATE TABLE auth_session (
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    account_id BIGINT NOT NULL,
    csrf_token VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_session_account FOREIGN KEY (account_id) REFERENCES account(id),
    CONSTRAINT ck_session_expiry CHECK (expires_at > created_at),
    KEY ix_session_expiry (expires_at),
    KEY ix_session_account (account_id)
) ENGINE=InnoDB;

CREATE TABLE policy_lock (
    id INT PRIMARY KEY,
    CONSTRAINT ck_policy_lock CHECK (id = 1)
) ENGINE=InnoDB;
INSERT INTO policy_lock (id) VALUES (1);

CREATE TABLE change_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    actor_id BIGINT NOT NULL,
    operation VARCHAR(64) NOT NULL,
    object_id BIGINT NULL,
    occurred_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_log_actor FOREIGN KEY (actor_id) REFERENCES account(id),
    KEY ix_log_time (occurred_at)
) ENGINE=InnoDB;

CREATE TABLE permission_template_source (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_batch_id BIGINT NOT NULL,
    resource_id BIGINT NOT NULL,
    source_cell VARCHAR(12) NOT NULL,
    role_label VARCHAR(50) NOT NULL,
    raw_text VARCHAR(100) NULL,
    review_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    CONSTRAINT fk_template_batch FOREIGN KEY (source_batch_id) REFERENCES source_batch(id),
    CONSTRAINT fk_template_resource FOREIGN KEY (resource_id) REFERENCES resource(id),
    CONSTRAINT uk_template_cell UNIQUE (source_batch_id, source_cell),
    CONSTRAINT ck_template_status CHECK (review_status IN ('PENDING', 'REVIEWED'))
) ENGINE=InnoDB;
