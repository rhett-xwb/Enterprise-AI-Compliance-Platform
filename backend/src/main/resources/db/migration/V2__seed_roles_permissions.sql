-- Seed metadata only; no user accounts or shared default passwords.
INSERT INTO sys_role(code, name) VALUES
('ADMIN', '系统管理员'), ('LEGAL', '法务与合规人员'), ('EMPLOYEE', '普通员工');

INSERT INTO sys_permission(code, name) VALUES
('user:manage', '用户管理'), ('document:read', '制度查询'),
('document:manage', '制度管理'), ('approval:submit', '提交申请'),
('approval:review', '处理审批'), ('contract:review', '合同审查');

INSERT INTO sys_role_permission(role_id, permission_id)
SELECT r.id, p.id FROM sys_role r CROSS JOIN sys_permission p WHERE r.code = 'ADMIN';

INSERT INTO sys_role_permission(role_id, permission_id)
SELECT r.id, p.id FROM sys_role r CROSS JOIN sys_permission p
WHERE r.code = 'LEGAL' AND p.code IN ('document:read','document:manage','approval:submit','approval:review','contract:review');

INSERT INTO sys_role_permission(role_id, permission_id)
SELECT r.id, p.id FROM sys_role r CROSS JOIN sys_permission p
WHERE r.code = 'EMPLOYEE' AND p.code IN ('document:read','approval:submit');
