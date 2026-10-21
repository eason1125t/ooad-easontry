-- Synthetic management resources and demonstration data. No original employee PII is seeded.
INSERT INTO organization(id,source_key,name) VALUES(1,'demo:company','市政公司（合成演示）');
INSERT INTO organization(id,source_key,name,parent_id) VALUES(2,'demo:office','演示办公室',1);
INSERT INTO employee(id,source_key,full_name,organization_id,position_name,synthetic)
VALUES(1,'demo:employee','演示员工',2,'演示岗位',1);
INSERT INTO resource(id,code,name,kind) VALUES(1,'management','权限管理平台','APPLICATION');
INSERT INTO resource(id,parent_id,code,name,kind) VALUES(2,1,'rbac','统一权限管理','MODULE');
INSERT INTO resource(id,parent_id,code,name,kind) VALUES
(3,2,'platform','平台入口','FEATURE'),(4,2,'accounts','账号管理','FEATURE'),
(5,2,'roles','角色管理','FEATURE'),(6,2,'resources','资源目录','FEATURE'),
(7,2,'permissions','操作权限','FEATURE'),(8,2,'grants','授权配置','FEATURE'),
(9,2,'employees','员工关联','FEATURE');
INSERT INTO resource(id,code,name,kind) VALUES(10,'demo','员工演示应用','APPLICATION');
INSERT INTO resource(id,parent_id,code,name,kind) VALUES(11,10,'demo.office','演示文档','MODULE');
INSERT INTO resource(id,parent_id,code,name,kind) VALUES(12,11,'demo.documents','文档清单','FEATURE');
INSERT INTO permission(id,resource_id,action,code,name) VALUES
(1,3,'access','platform:access','进入管理平台'),
(2,4,'read','accounts:read','查看账号'),(3,4,'write','accounts:write','维护账号'),
(4,5,'read','roles:read','查看角色'),(5,5,'write','roles:write','维护角色'),
(6,6,'read','resources:read','查看资源'),(7,6,'write','resources:write','维护资源'),
(8,7,'read','permissions:read','查看操作权限'),(9,7,'write','permissions:write','维护操作权限'),
(10,8,'read','grants:read','查看授权'),(11,8,'write','grants:write','调整授权'),
(12,9,'read','employees:read','读取脱敏员工选项'),
(13,12,'read','demo.documents:read','查看演示文档');
INSERT INTO role(id,code,name,description) VALUES
(1,'platform-admin','平台管理角色','明确授予各项管理操作权限，不提供永久绕过'),
(2,'demo-reader','文档阅读角色','仅可查看合成演示文档');
INSERT INTO role_permission(role_id,permission_id) SELECT 1,id FROM permission;
INSERT INTO role_permission(role_id,permission_id) VALUES(2,13);
