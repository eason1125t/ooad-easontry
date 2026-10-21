// Application-authenticated batch decisions for external backends; no administrator cookie fallback.
// Uses one short repeatable-read view through Store; EmployeeProcesses owns grant mutations.
package edu.municipal.access;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class AuthorizationProcesses {
    private static final Logger LOG = LoggerFactory.getLogger(AuthorizationProcesses.class);
    private final Store db;
    private final Clock clock;
    private final TransactionTemplate snapshot;
    public AuthorizationProcesses(Store db, Clock clock, PlatformTransactionManager manager) {
        this.db = db; this.clock = clock; snapshot = new TransactionTemplate(manager);
        snapshot.setReadOnly(true);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setTimeout(2);
    }
    public Map<String,Object> check(String key, JsonNode body, String requestId) {
        try { return snapshot.execute(status -> decide(key, body, requestId)); }
        catch (DataAccessException | TransactionException failure) {
            throw new Problem(503, "AUTHORIZATION_UNAVAILABLE", "鉴权服务暂不可用");
        }
    }
    private Map<String,Object> decide(String key, JsonNode body, String requestId) {
        if (key == null || !key.matches("[a-f0-9]{32}\\.[A-Za-z0-9_-]{43}")) throw credentialFailure();
        String[] parts = key.split("\\.", 2);
        // First consistent read establishes this batch's snapshot, including credential revocation.
        var clients = db.list("SELECT k.secret_hash,k.enabled AS key_enabled,k.expires_at,c.id,c.application_id,c.enabled AS client_enabled,a.enabled,a.kind,a.code FROM integration_credential k JOIN integration_client c ON c.id=k.client_id JOIN resource a ON a.id=c.application_id WHERE k.id=#{p.id}", "id",parts[0]);
        if (clients.isEmpty()) throw credentialFailure();
        var client = clients.getFirst();
        if (!MessageDigest.isEqual(SessionProcesses.hash(parts[1]).getBytes(StandardCharsets.UTF_8), Objects.toString(client.get("secretHash")).getBytes(StandardCharsets.UTF_8))
                || !truth(client.get("keyEnabled")) || !truth(client.get("clientEnabled")) || !truth(client.get("enabled"))
                || !"APPLICATION".equals(client.get("kind")) || "management".equals(client.get("code"))
                || (client.get("expiresAt") instanceof Instant expiry && !clock.instant().isBefore(expiry))) throw credentialFailure();
        if (body == null || !body.isObject() || body.size()!=2 || !body.has("employeeId") || !body.has("permissionCodes")) throw invalid();
        var employee = body.get("employeeId"); var requested = body.get("permissionCodes");
        if (!employee.isIntegralNumber() || !employee.canConvertToLong() || employee.asLong()<=0 || !requested.isArray() || requested.isEmpty() || requested.size()>50) throw invalid();
        List<String> codes = new ArrayList<>();
        for (var item : requested) {
            if (!item.isTextual() || !item.asText().matches("[a-z][a-z0-9._-]{1,99}:[a-z][a-z0-9._-]{1,39}") || codes.contains(item.asText())) throw invalid();
            codes.add(item.asText());
        }
        long application = ((Number)client.get("applicationId")).longValue(), employeeId = employee.asLong();
        // Only internally generated placeholder names enter SQL; all permission codes stay bound values.
        List<Object> bindings = new ArrayList<>(); List<String> placeholders = new ArrayList<>();
        for (int index=0; index<codes.size(); index++) {
            String name="code"+index; placeholders.add("#{p."+name+"}"); bindings.add(name); bindings.add(codes.get(index));
        }
        var known = db.list("SELECT p.code,a.id AS application_id FROM permission p JOIN resource f ON f.id=p.resource_id JOIN resource m ON m.id=f.parent_id JOIN resource a ON a.id=m.parent_id WHERE p.code IN ("+String.join(",",placeholders)+")", bindings.toArray());
        if (known.stream().anyMatch(row -> ((Number)row.get("applicationId")).longValue()!=application)) {
            LOG.info("authorization requestId={} clientId={} employeeId={} outcome=APPLICATION_SCOPE_DENIED",requestId,client.get("id"),employeeId);
            throw new Problem(403,"APPLICATION_SCOPE_DENIED","请求超出接入应用范围");
        }
        var staff = db.list("SELECT enabled FROM employee WHERE id=#{p.id}","id",employeeId);
        boolean available = !staff.isEmpty() && truth(staff.getFirst().get("enabled"));
        Set<String> actual = available ? effective(employeeId,application) : Set.of();
        List<Map<String,Object>> results = codes.stream().map(code -> {
            boolean allowed=available && actual.contains(code);
            return Map.<String,Object>of("permissionCode",code,"allowed",allowed,"reason",!available ? "EMPLOYEE_UNAVAILABLE" : allowed ? "ALLOWED" : "NO_PERMISSION");
        }).toList();
        LOG.info("authorization requestId={} clientId={} employeeId={} codes={} results={}",requestId,client.get("id"),employeeId,codes,results);
        return Map.of("employeeId",employeeId,"results",results,"requestId",requestId);
    }
    public Set<String> effective(long employeeId, long application) {
        String sql="""
            WITH RECURSIVE available AS (
                SELECT id FROM resource WHERE id=#{p.app} AND enabled=1 AND kind='APPLICATION' AND code<>'management'
                UNION ALL SELECT r.id FROM resource r JOIN available a ON a.id=r.parent_id WHERE r.enabled=1
            )
            SELECT DISTINCT p.code FROM employee e JOIN employee_role er ON er.employee_id=e.id
            JOIN role r ON r.id=er.role_id AND r.enabled=1 AND r.purpose='BUSINESS'
            JOIN role_permission rp ON rp.role_id=r.id JOIN permission p ON p.id=rp.permission_id AND p.enabled=1
            JOIN available a ON a.id=p.resource_id WHERE e.id=#{p.employee} AND e.enabled=1 ORDER BY p.code
            """;
        Set<String> codes=new LinkedHashSet<>();
        db.list(sql,"app",application,"employee",employeeId).forEach(row->codes.add((String)row.get("code")));
        return codes;
    }
    static boolean truth(Object value) { return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue()==1; }
    private Problem invalid() { return new Problem(400,"INVALID_CHECK_REQUEST","请求须包含员工编号和 1–50 个不重复的有效权限码"); }
    private Problem credentialFailure() { return new Problem(401,"INVALID_APP_CREDENTIAL","应用凭据无效或不可用"); }
}
