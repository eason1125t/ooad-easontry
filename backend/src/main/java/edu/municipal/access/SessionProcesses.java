// Local BCrypt authentication and absolute database sessions. Raw random tokens are never stored.

package edu.municipal.access;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class SessionProcesses {
    public record Identity(long accountId, String tokenHash, String csrfToken, Instant expiresAt) {}
    public record Login(Identity identity, String token) {}
    private record Attempt(int failures, Instant until) {}
    private final Store db;
    private final Clock clock;
    private final int seconds;
    private final PermissionProcesses permissions;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private final String dummyHash = encoder.encode("dummy-credential-unavailable");
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();
    public SessionProcesses(Store db, Clock clock, PermissionProcesses permissions, @Value("${app.session-seconds:1800}") int seconds) {
        this.db = db; this.clock = clock; this.permissions = permissions; this.seconds = seconds;
    }
    public Login login(Map<String, Object> body, String remoteAddress) {
        String username = ManagementProcesses.text(body, "username", 64);
        Object password = body.get("password");
        if (!(password instanceof String value) || value.length() > 64 || value.getBytes(StandardCharsets.UTF_8).length > 72) throw Problem.bad("密码格式不正确");
        // MySQL TIMESTAMP(6) stores microseconds; return exactly the persisted expiry.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        attempts.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().until()));
        String key = remoteAddress + ":" + username;
        Attempt previous = attempts.get(key);
        if ((previous != null && previous.failures() >= 5) || attempts.size() > 10000) throw new Problem(429, "LOGIN_THROTTLED", "登录失败次数较多，请五分钟后重试");
        List<Map<String, Object>> rows = db.list("SELECT a.id,a.enabled,a.locked,c.password_hash FROM account a JOIN credential c ON c.account_id=a.id WHERE a.username=#{p.username}", "username", username);
        Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.getFirst();
        boolean matches = encoder.matches(value, Objects.toString(row.getOrDefault("passwordHash", dummyHash)));
        if (row.isEmpty() || !matches || !Boolean.TRUE.equals(row.get("enabled")) || Boolean.TRUE.equals(row.get("locked"))) {
            attempts.compute(key, (ignored, existing) -> new Attempt(existing == null ? 1 : existing.failures() + 1, existing == null ? now.plusSeconds(300) : existing.until()));
            throw new Problem(401, "INVALID_CREDENTIALS", "账号或密码不正确，或账号不可用");
        }
        attempts.remove(key);
        String token = randomToken(), csrf = randomToken();
        Instant expiry = now.plusSeconds(seconds);
        long accountId = ((Number) row.get("id")).longValue();
        db.execute("INSERT INTO auth_session(token_hash,account_id,csrf_token,created_at,expires_at) VALUES(#{p.hash},#{p.id},#{p.csrf},#{p.created},#{p.expires})", "hash", hash(token), "id", accountId, "csrf", csrf, "created", Timestamp.from(now), "expires", Timestamp.from(expiry));
        return new Login(new Identity(accountId, hash(token), csrf, expiry), token);
    }
    public Identity authenticate(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw unauthenticated();
        String tokenHash = hash(token);
        List<Map<String, Object>> rows = db.list("SELECT s.*,a.enabled,a.locked FROM auth_session s JOIN account a ON a.id=s.account_id WHERE token_hash=#{p.hash}", "hash", tokenHash);
        if (rows.isEmpty()) throw unauthenticated();
        Map<String, Object> row = rows.getFirst();
        Instant expiry = (Instant) row.get("expiresAt");
        // Equality is expired. No request updates expires_at or replaces the browser cookie.
        if (!clock.instant().isBefore(expiry) || !Boolean.TRUE.equals(row.get("enabled")) || Boolean.TRUE.equals(row.get("locked"))) {
            db.execute("DELETE FROM auth_session WHERE token_hash=#{p.hash}", "hash", tokenHash);
            throw unauthenticated();
        }
        return new Identity(((Number) row.get("accountId")).longValue(), tokenHash, (String) row.get("csrfToken"), expiry);
    }
    public Map<String, Object> me(Identity identity) {
        Map<String, Object> result = new LinkedHashMap<>(db.one("SELECT id,username,display_name,employee_id FROM account WHERE id=#{p.id}", "id", identity.accountId()));
        result.put("permissions", permissions.effective(identity.accountId()));
        result.put("roles", db.list("SELECT r.id,r.code,r.name,r.enabled FROM role r JOIN user_role ur ON ur.role_id=r.id WHERE ur.account_id=#{p.id}", "id", identity.accountId()));
        result.put("csrfToken", identity.csrfToken()); result.put("expiresAt", identity.expiresAt());
        Object employee = result.get("employeeId");
        if (employee != null) result.put("employee", db.one("SELECT e.id,IF(e.synthetic,e.full_name,CONCAT('员工 #',e.id)) AS label,o.name AS organization_name,e.position_name FROM employee e JOIN organization o ON o.id=e.organization_id WHERE e.id=#{p.id}", "id", employee));
        return result;
    }
    public void logout(Identity identity) { db.execute("DELETE FROM auth_session WHERE token_hash=#{p.hash}", "hash", identity.tokenHash()); }
    private String randomToken() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private Problem unauthenticated() { return new Problem(401, "UNAUTHENTICATED", "会话无效或已到期，请重新登录"); }
}
