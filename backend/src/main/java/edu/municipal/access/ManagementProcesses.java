// Functional account/role/catalog/grant processes. Transactions hold the policy lock,
// check optimistic versions, apply changes and validate the last-admin invariant atomically.

package edu.municipal.access;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class ManagementProcesses {
    private final Store db;
    private final PermissionProcesses permissions;
    private final TransactionTemplate transaction;
    private final int passwordMinimum;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    public ManagementProcesses(Store db, PermissionProcesses permissions, PlatformTransactionManager manager,
            @Value("${app.password-min-length:12}") int passwordMinimum) {
        this.db = db; this.permissions = permissions;
        this.transaction = new TransactionTemplate(manager);
        this.passwordMinimum = passwordMinimum;
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    <T> T change(long actor, String operation, String required, Supplier<T> action) {
        return transaction.execute(status -> {
            permissions.lockPolicy();
            permissions.require(actor, required);
            T result = action.get();
            permissions.ensureAdministratorRemains();
            db.execute("INSERT INTO change_log(actor_id,operation) VALUES(#{p.actor},#{p.operation})", "actor", actor, "operation", operation);
            return result;
        });
    }
    public static String text(Map<String, Object> body, String field, int maximum) {
        Object value = body.get(field);
        if (!(value instanceof String string) || string.isBlank() || string.length() > maximum) throw Problem.bad(field + " 不能为空或超出长度");
        return string.trim();
    }
    public static String code(Map<String, Object> body, String field, int maximum) {
        String value = text(body, field, maximum);
        if (!value.matches("[a-z][a-z0-9._-]{1," + (maximum - 1) + "}")) throw Problem.bad(field + " 必须使用小写字母、数字、点、短横线或下划线");
        return value;
    }
    public static boolean flag(Map<String, Object> body, String key) {
        if (!(body.get(key) instanceof Boolean bool)) throw Problem.bad(key + " 必须为布尔值");
        return bool;
    }
    public static long number(Map<String, Object> body, String field) {
        if (!(body.get(field) instanceof Number value) || value.longValue() < 0 || value.doubleValue() != value.longValue()) throw Problem.bad(field + " 必须为非负整数");
        return value.longValue();
    }
    private static Object employee(Map<String, Object> body) { return body.get("employeeId") == null ? null : number(body, "employeeId"); }
    private void updated(int count) { if (count != 1) throw Problem.conflict(); }
    public Map<String, Object> accounts(int page, int size, String query) {
        page = Math.max(1, page); size = Math.min(100, Math.max(1, size));
        List<Map<String, Object>> rows = db.list("SELECT id,username,display_name,employee_id,enabled,locked,version,grants_version,created_at FROM account WHERE username LIKE CONCAT('%',#{p.q},'%') OR display_name LIKE CONCAT('%',#{p.q},'%') ORDER BY id LIMIT #{p.size} OFFSET #{p.offset}", "q", query, "size", size, "offset", (long) (page - 1) * size);
        long total = db.number("SELECT COUNT(*) FROM account WHERE username LIKE CONCAT('%',#{p.q},'%') OR display_name LIKE CONCAT('%',#{p.q},'%')", "q", query);
        return Map.of("items", rows, "total", total, "page", page, "pageSize", size);
    }
    public long createAccount(long actor, Map<String, Object> body) {
        return change(actor, "account.create", "accounts:write", () -> {
            long id = db.insert("INSERT INTO account(username,display_name,employee_id) VALUES(#{p.username},#{p.name},#{p.employee})", Store.parameters("username", code(body, "username", 64), "name", text(body, "displayName", 80), "employee", employee(body)));
            String password = password(body);
            db.execute("INSERT INTO credential(account_id,password_hash) VALUES(#{p.id},#{p.hash})", "id", id, "hash", encoder.encode(password));
            return id;
        });
    }
    public void updateAccount(long actor, long id, Map<String, Object> body) {
        change(actor, "account.update", "accounts:write", () -> {
            updated(db.execute("UPDATE account SET display_name=#{p.name},employee_id=#{p.employee},enabled=#{p.enabled},locked=#{p.locked},version=version+1 WHERE id=#{p.id} AND version=#{p.version}", "id", id, "version", number(body, "version"), "name", text(body, "displayName", 80), "employee", employee(body), "enabled", flag(body, "enabled"), "locked", flag(body, "locked")));
            if (!flag(body, "enabled") || flag(body, "locked")) db.execute("DELETE FROM auth_session WHERE account_id=#{p.id}", "id", id);
            return null;
        });
    }
    private String password(Map<String, Object> body) {
        if (!(body.get("password") instanceof String value) || value.isBlank() || value.length() < passwordMinimum || value.length() > 64 || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) throw Problem.bad("密码须为 " + passwordMinimum + "–64 位且不超过 72 个 UTF-8 字节");
        // Passwords are deliberately not trimmed: spaces are legitimate credential characters.
        return value;
    }
    public void resetPassword(long actor, long id, Map<String, Object> body) {
        change(actor, "account.password.reset", "accounts:write", () -> {
            updated(db.execute("UPDATE account SET version=version+1 WHERE id=#{p.id} AND version=#{p.version}", "id", id, "version", number(body, "version")));
            db.execute("UPDATE credential SET password_hash=#{p.hash},changed_at=CURRENT_TIMESTAMP(6) WHERE account_id=#{p.id}", "id", id, "hash", encoder.encode(password(body)));
            db.execute("DELETE FROM auth_session WHERE account_id=#{p.id}", "id", id);
            return null;
        });
    }
    public List<Map<String, Object>> roles() { return db.list("SELECT * FROM role ORDER BY id"); }
    public List<Map<String, Object>> resources() { return db.list("SELECT id,parent_id,code,name,kind,enabled,version FROM resource ORDER BY id"); }
    public List<Map<String, Object>> permissionList() { return db.list("SELECT p.*,r.name AS resource_name,a.code AS application_code FROM permission p JOIN resource r ON r.id=p.resource_id JOIN resource m ON m.id=r.parent_id JOIN resource a ON a.id=m.parent_id ORDER BY p.id"); }
    public long saveRole(long actor, Long id, Map<String, Object> body) {
        return change(actor, "role.save", "roles:write", () -> {
            String name = text(body, "name", 80);
            String description = Objects.toString(body.getOrDefault("description", ""), "");
            if (description.length() > 300) throw Problem.bad("说明最多 300 字");
            if (id == null) {
                String purpose=Objects.toString(body.getOrDefault("purpose","BUSINESS"));
                if(!Set.of("MANAGEMENT","BUSINESS").contains(purpose)) throw Problem.bad("请选择管理角色或业务角色");
                return db.insert("INSERT INTO role(code,name,description,purpose) VALUES(#{p.code},#{p.name},#{p.description},#{p.purpose})", Store.parameters("code",code(body,"code",80),"name",name,"description",description,"purpose",purpose));
            }
            if(body.containsKey("purpose") && !body.get("purpose").equals(db.one("SELECT purpose FROM role WHERE id=#{p.id}","id",id).get("purpose"))) throw Problem.bad("角色用途创建后不可变更");
            updated(db.execute("UPDATE role SET name=#{p.name},description=#{p.description},enabled=#{p.enabled},version=version+1 WHERE id=#{p.id} AND version=#{p.version}", "id", id, "version", number(body, "version"), "name", name, "description", description, "enabled", flag(body, "enabled")));
            return id;
        });
    }
    public long saveResource(long actor, Long id, Map<String, Object> body) {
        return change(actor, "resource.save", "resources:write", () -> {
            String kind = text(body, "kind", 16), name = text(body, "name", 100);
            Object parent = body.get("parentId");
            if (kind.equals("APPLICATION")) {
                if (parent != null) throw Problem.bad("应用节点不能有父节点");
            } else {
                if (!Set.of("MODULE", "FEATURE").contains(kind) || parent == null) throw Problem.bad("请选择资源类型及父节点");
                long parentId = number(body, "parentId");
                String expected = kind.equals("MODULE") ? "APPLICATION" : "MODULE";
                if (!expected.equals(db.one("SELECT kind FROM resource WHERE id=#{p.id}", "id", parentId).get("kind"))) throw Problem.bad("资源必须按应用、模块、功能点分层");
            }
            if (id == null) return db.insert("INSERT INTO resource(parent_id,code,name,kind) VALUES(#{p.parent},#{p.code},#{p.name},#{p.kind})", Store.parameters("parent", parent, "code", code(body, "code", 100), "name", name, "kind", kind));
            Map<String, Object> current = db.one("SELECT kind,parent_id FROM resource WHERE id=#{p.id}", "id", id);
            if (!kind.equals(current.get("kind")) || !Objects.equals(parent == null ? null : ((Number) parent).longValue(), current.get("parentId") == null ? null : ((Number) current.get("parentId")).longValue())) throw Problem.bad("已有资源类型及父节点保持稳定，请创建新目录再迁移授权");
            updated(db.execute("UPDATE resource SET name=#{p.name},enabled=#{p.enabled},version=version+1 WHERE id=#{p.id} AND version=#{p.version}", "id", id, "version", number(body, "version"), "name", name, "enabled", flag(body, "enabled")));
            return id;
        });
    }
    public long savePermission(long actor, Long id, Map<String, Object> body) {
        return change(actor, "permission.save", "permissions:write", () -> {
            String name = text(body, "name", 100);
            if (id == null) {
                long resource = number(body, "resourceId");
                Map<String, Object> target = db.one("SELECT kind,code FROM resource WHERE id=#{p.id}", "id", resource);
                if (!"FEATURE".equals(target.get("kind"))) throw Problem.bad("操作权限必须属于功能点");
                String action = code(body, "action", 40);
                return db.insert("INSERT INTO permission(resource_id,action,code,name) VALUES(#{p.resource},#{p.action},#{p.code},#{p.name})", Store.parameters("resource", resource, "action", action, "code", target.get("code") + ":" + action, "name", name));
            }
            updated(db.execute("UPDATE permission SET name=#{p.name},enabled=#{p.enabled},version=version+1 WHERE id=#{p.id} AND version=#{p.version}", "id", id, "version", number(body, "version"), "name", name, "enabled", flag(body, "enabled")));
            return id;
        });
    }
    public Map<String, Object> grants(boolean account, long id) {
        String owner = account ? "account" : "role", relation = account ? "user_role" : "role_permission", ownerKey = account ? "account_id" : "role_id", targetKey = account ? "role_id" : "permission_id";
        Map<String, Object> record = db.one("SELECT grants_version FROM " + owner + " WHERE id=#{p.id}", "id", id);
        return Map.of("version", record.get("grantsVersion"), "ids", db.list("SELECT " + targetKey + " AS id FROM " + relation + " WHERE " + ownerKey + "=#{p.id}", "id", id).stream().map(row -> row.get("id")).toList());
    }
    public void replaceGrants(long actor, boolean account, long id, Map<String, Object> body) {
        change(actor, account ? "user.roles.replace" : "role.permissions.replace", "grants:write", () -> {
            Set<Long> unique=grantIds(body);
            String owner = account ? "account" : "role", relation = account ? "user_role" : "role_permission", ownerKey = account ? "account_id" : "role_id", targetKey = account ? "role_id" : "permission_id", target = account ? "role" : "permission";
            updated(db.execute("UPDATE " + owner + " SET grants_version=grants_version+1 WHERE id=#{p.id} AND grants_version=#{p.version}", "id", id, "version", number(body, "version")));
            String purpose=account ? "MANAGEMENT" : (String)db.one("SELECT purpose FROM role WHERE id=#{p.id}","id",id).get("purpose");
            for(long targetId:unique) {
                if(account) {
                    if(!"MANAGEMENT".equals(db.one("SELECT purpose FROM role WHERE id=#{p.id}","id",targetId).get("purpose"))) throw Problem.bad("管理账号只能分配管理角色，业务角色请分配给员工");
                } else {
                    String app=(String)db.one("SELECT a.code FROM permission p JOIN resource f ON f.id=p.resource_id JOIN resource m ON m.id=f.parent_id JOIN resource a ON a.id=m.parent_id WHERE p.id=#{p.id}","id",targetId).get("code");
                    if("MANAGEMENT".equals(purpose)!= "management".equals(app)) throw Problem.bad("管理角色与业务角色的权限范围不可混用");
                }
            }
            db.execute("DELETE FROM " + relation + " WHERE " + ownerKey + "=#{p.id}", "id", id);
            for (long targetId : unique) db.execute("INSERT INTO " + relation + "(" + ownerKey + "," + targetKey + ") VALUES(#{p.id},#{p.target})", "id", id, "target", targetId);
            return null;
        });
    }
    static Set<Long> grantIds(Map<String,Object> body) {
        if(!(body.get("ids") instanceof List<?> ids) || ids.size()>5000) throw Problem.bad("请选择不超过 5000 个授权对象");
        Set<Long> unique=new LinkedHashSet<>();
        for(Object value:ids) if(!(value instanceof Number n) || n.longValue()<=0 || n.doubleValue()!=n.longValue() || !unique.add(n.longValue())) throw Problem.bad("授权对象必须为不重复的正整数 ID");
        return unique;
    }
    public Map<String, Object> employees(int page, String query) {
        String term = query.trim();
        if (term.length() > 80) throw Problem.bad("员工查询条件最多 80 字");
        int currentPage = Math.max(1, page);
        // Search only identifiers and visible labels; real employee names/phones are never a search oracle.
        boolean identifier = term.matches("(?:员工\\s*#?|#)?\\s*[0-9]+");
        String condition = identifier ? "CAST(e.id AS CHAR)=#{p.employeeId}"
            : "(#{p.q}='' OR o.name LIKE CONCAT('%',#{p.q},'%') OR (e.synthetic=1 AND e.full_name LIKE CONCAT('%',#{p.q},'%')))";
        String source = " FROM employee e JOIN organization o ON o.id=e.organization_id LEFT JOIN account linked ON linked.employee_id=e.id WHERE " + condition;
        String employeeId = identifier ? term.replaceAll("[^0-9]", "") : "";
        List<Map<String, Object>> rows = db.list("SELECT e.id,e.enabled,e.version,e.grants_version,IF(e.synthetic,e.full_name,CONCAT('员工 #',e.id)) AS label,o.name AS organization_name,linked.id AS linked_account_id" + source + " ORDER BY e.id LIMIT 100 OFFSET #{p.offset}",
            "q", term, "employeeId", employeeId, "offset", (long) (currentPage - 1) * 100);
        return Map.of("items", rows, "total", db.number("SELECT COUNT(*)" + source, "q", term, "employeeId", employeeId), "page", currentPage, "pageSize", 100);
    }
}
