-- Employee business grants and scoped integration clients; preserves V1.1-V3 and source data.
-- Used by EmployeeProcesses and AuthorizationProcesses; credentials are provisioned locally.
ALTER TABLE employee ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN grants_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE role ADD COLUMN purpose VARCHAR(16) NOT NULL DEFAULT 'BUSINESS',
    ADD CONSTRAINT ck_role_purpose CHECK (purpose IN ('MANAGEMENT','BUSINESS'));
UPDATE role r SET purpose='MANAGEMENT' WHERE EXISTS (
    SELECT 1 FROM role_permission rp JOIN permission p ON p.id=rp.permission_id
    WHERE rp.role_id=r.id AND p.code='platform:access');
CREATE TABLE employee_role (
    employee_id BIGINT NOT NULL, role_id BIGINT NOT NULL,
    PRIMARY KEY(employee_id,role_id), KEY ix_employee_role_reverse(role_id),
    CONSTRAINT fk_er_employee FOREIGN KEY(employee_id) REFERENCES employee(id),
    CONSTRAINT fk_er_role FOREIGN KEY(role_id) REFERENCES role(id)
) ENGINE=InnoDB;
INSERT INTO employee_role(employee_id,role_id)
    SELECT DISTINCT a.employee_id,ur.role_id FROM account a
    JOIN user_role ur ON ur.account_id=a.id JOIN role r ON r.id=ur.role_id
    WHERE a.employee_id IS NOT NULL AND r.purpose='BUSINESS';
-- Preserve legacy account/grant history, disable business-only logins and revoke their sessions.
UPDATE account a SET enabled=FALSE,version=version+1 WHERE NOT EXISTS (
    SELECT 1 FROM user_role ur JOIN role r ON r.id=ur.role_id
    WHERE ur.account_id=a.id AND r.purpose='MANAGEMENT');
DELETE s FROM auth_session s JOIN account a ON a.id=s.account_id WHERE a.enabled=FALSE;
CREATE TABLE integration_client (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, code VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    application_id BIGINT NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_client_application FOREIGN KEY(application_id) REFERENCES resource(id)
) ENGINE=InnoDB;
CREATE TABLE integration_credential (
    id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    client_id BIGINT NOT NULL, secret_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE, expires_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY ix_credential_client(client_id),
    CONSTRAINT fk_credential_client FOREIGN KEY(client_id) REFERENCES integration_client(id)
) ENGINE=InnoDB;
INSERT INTO resource(code,name,kind) VALUES('office','公文业务系统','APPLICATION');
INSERT INTO resource(parent_id,code,name,kind) SELECT id,'office.work','公文管理','MODULE' FROM resource WHERE code='office';
INSERT INTO resource(parent_id,code,name,kind) SELECT id,'office.documents','公文文档','FEATURE' FROM resource WHERE code='office.work';
INSERT INTO permission(resource_id,action,code,name)
    SELECT id,'read','office.documents:read','查看公文' FROM resource WHERE code='office.documents';
INSERT INTO permission(resource_id,action,code,name)
    SELECT id,'update','office.documents:update','修改公文' FROM resource WHERE code='office.documents';
INSERT INTO role(code,name,description,purpose) VALUES
    ('office-editor','公文编辑角色','合成验证：读取和修改公文','BUSINESS'),
    ('office-reader','公文阅读角色','合成验证：仅读取公文','BUSINESS');
INSERT INTO role_permission(role_id,permission_id)
    SELECT r.id,p.id FROM role r CROSS JOIN permission p WHERE r.code='office-editor' AND p.code IN ('office.documents:read','office.documents:update');
INSERT INTO role_permission(role_id,permission_id)
    SELECT r.id,p.id FROM role r CROSS JOIN permission p WHERE r.code='office-reader' AND p.code='office.documents:read';
INSERT INTO employee(source_key,full_name,organization_id,position_name,synthetic)
    VALUES('demo:office-editor','公文编辑员工（合成）',2,'公文编辑',TRUE),
          ('demo:office-reader','公文阅读员工（合成）',2,'公文阅读',TRUE);
INSERT INTO employee_role(employee_id,role_id)
    SELECT e.id,r.id FROM employee e JOIN role r ON
        (e.source_key='demo:office-editor' AND r.code='office-editor') OR
        (e.source_key='demo:office-reader' AND r.code='office-reader');
-- Remove business permissions from management roles; no account-role history is deleted.
DELETE rp FROM role_permission rp JOIN role r ON r.id=rp.role_id
    JOIN permission p ON p.id=rp.permission_id JOIN resource f ON f.id=p.resource_id
    JOIN resource m ON m.id=f.parent_id JOIN resource a ON a.id=m.parent_id
    WHERE r.purpose='MANAGEMENT' AND a.code<>'management';
