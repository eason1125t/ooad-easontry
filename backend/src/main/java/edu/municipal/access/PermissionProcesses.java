// RBAC0 set-union calculation and the last-complete-administrator invariant.
// Called by HTTP guards and every serialized configuration transaction; no role-name bypass.

package edu.municipal.access;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class PermissionProcesses {
    public static final Set<String> MANAGEMENT = Set.of("platform:access", "accounts:read", "accounts:write",
        "roles:read", "roles:write", "resources:read", "resources:write", "permissions:read",
        "permissions:write", "grants:read", "grants:write", "employees:read");
    private static final String EFFECTIVE = """
        WITH RECURSIVE available_resource AS (
            SELECT id FROM resource WHERE parent_id IS NULL AND enabled = 1
            UNION ALL SELECT child.id FROM resource child
                JOIN available_resource parent ON child.parent_id = parent.id WHERE child.enabled = 1
        )
        SELECT DISTINCT p.code FROM account a
        JOIN user_role ur ON ur.account_id = a.id
        JOIN role r ON r.id = ur.role_id AND r.enabled = 1
        JOIN role_permission rp ON rp.role_id = r.id
        JOIN permission p ON p.id = rp.permission_id AND p.enabled = 1
        JOIN available_resource ar ON ar.id = p.resource_id
        WHERE a.id = #{p.id} AND a.enabled = 1 AND a.locked = 0 ORDER BY p.code
        """;
    private final Store db;
    public PermissionProcesses(Store db) { this.db = db; }
    public Set<String> effective(long accountId) {
        Set<String> codes = new LinkedHashSet<>();
        db.list(EFFECTIVE, "id", accountId).forEach(row -> codes.add((String) row.get("code")));
        return codes;
    }
    public void require(long accountId, String permission) {
        Set<String> actual = effective(accountId);
        if (!actual.contains(permission) || (!permission.startsWith("demo.") && !actual.contains("platform:access"))) {
            throw new Problem(403, "FORBIDDEN", "当前账号没有执行此操作的权限");
        }
    }
    public void lockPolicy() { db.one("SELECT id FROM policy_lock WHERE id=1 FOR UPDATE"); }
    public void ensureAdministratorRemains() {
        // The shared database lock prevents two administrators from removing each other's last access concurrently.
        boolean exists = db.list("SELECT id FROM account WHERE enabled=1 AND locked=0").stream()
            .anyMatch(row -> effective(((Number) row.get("id")).longValue()).containsAll(MANAGEMENT));
        if (!exists) throw new Problem(409, "LAST_ADMINISTRATOR", "必须保留至少一个有效且具有完整管理权限的账号");
    }
}
