// Real MySQL integration tests in municipal_access_ooad_test, never the development schema.
// Cover grant union/revocation, rollback, optimistic concurrency, CSRF and absolute expiry.

package edu.municipal.access;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.Cookie;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(RbacIntegrationTest.TestTime.class)
class RbacIntegrationTest {
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired Store db;
    @Autowired ManagementProcesses management;
    @Autowired PermissionProcesses permissions;
    @Autowired MutableClock clock;
    private static final String PASSWORD = "1234"; // User-selected local test credential only.
    private static final String HASH = new BCryptPasswordEncoder(12).encode(PASSWORD);
    private String prefix;
    private record Session(Cookie cookie, String csrf, Instant expiresAt) {}

    @DynamicPropertySource static void testDatabase(DynamicPropertyRegistry registry) throws Exception {
        Properties local = new Properties();
        try (var input = Files.newInputStream(Path.of("../.local/application-local.properties"))) { local.load(input); }
        registry.add("spring.datasource.url", () -> "jdbc:mysql://localhost:3306/municipal_access_ooad_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        registry.add("spring.datasource.username", () -> local.getProperty("spring.datasource.username"));
        registry.add("spring.datasource.password", () -> local.getProperty("spring.datasource.password"));
        registry.add("spring.flyway.user", () -> local.getProperty("spring.flyway.user"));
        registry.add("spring.flyway.password", () -> local.getProperty("spring.flyway.password"));
    }
    @TestConfiguration static class TestTime { @Bean @Primary MutableClock mutableClock() { return new MutableClock(); } }
    static class MutableClock extends Clock {
        volatile Instant value = Instant.parse("2026-10-20T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return value; }
    }
    @BeforeEach void prepare() { prefix = "t_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12); clock.value = Instant.parse("2026-10-20T00:00:00Z"); }
    private long user(String suffix) {
        long id = db.insert("INSERT INTO account(username,display_name) VALUES(#{p.name},'合成测试账号')", Store.parameters("name", prefix + suffix));
        db.execute("INSERT INTO credential(account_id,password_hash) VALUES(#{p.id},#{p.hash})", "id", id, "hash", HASH);
        long loginRole=role("_login_"+id);
        db.execute("INSERT INTO role_permission VALUES(#{p.role},1)","role",loginRole);
        db.execute("INSERT INTO user_role VALUES(#{p.user},#{p.role})","user",id,"role",loginRole);
        return id;
    }
    private long role(String suffix) { return db.insert("INSERT INTO role(code,name,purpose) VALUES(#{p.code},'合成测试角色','MANAGEMENT')", Store.parameters("code", prefix + suffix)); }
    private Session login(String name) throws Exception {
        MvcResult result = http.perform(post("/api/session/login").contentType("application/json").content(json.writeValueAsString(Map.of("username",name,"password",PASSWORD)))).andExpect(status().isOk()).andReturn();
        String cookie = result.getResponse().getHeader("Set-Cookie").split(";",2)[0].substring((SecurityGate.COOKIE+"=").length());
        var body = json.readTree(result.getResponse().getContentAsString());
        return new Session(new Cookie(SecurityGate.COOKIE,cookie),body.path("csrfToken").asText(),Instant.parse(body.path("expiresAt").asText()));
    }
    private Session admin() throws Exception { return login("project_admin"); }
    private long adminId() { return db.number("SELECT id FROM account WHERE username='project_admin'"); }
    private MockHttpServletRequestBuilder requestPut(String path, Session session, Object body) throws Exception { return put(path).cookie(session.cookie()).header("X-CSRF-Token",session.csrf()).contentType("application/json").content(json.writeValueAsString(body)); }

    @Test void anonymousAndSpoofedIdentityAreRejected() throws Exception {
        http.perform(get("/api/accounts")).andExpect(status().isUnauthorized());
        http.perform(get("/api/accounts").header("X-User-Id","1").header("X-Role","admin")).andExpect(status().isUnauthorized());
    }
    @Test void ordinaryAccountCannotReadManagementAndCsrfIsRequired() throws Exception {
        http.perform(post("/api/session/login").contentType("application/json").content("{\"username\":\"demo_user\",\"password\":\"1234\"}")).andExpect(status().isUnauthorized());
        Session admin = admin();
        http.perform(post("/api/roles").cookie(admin.cookie()).contentType("application/json").content("{\"code\":\"untrusted\",\"name\":\"test\"}")).andExpect(status().isForbidden());
    }
    @Test void grantsAreUnionedAndRevocationAffectsTheNextRequest() throws Exception {
        long user = user("_u"), first = role("_a"), second = role("_b");
        db.execute("INSERT INTO user_role VALUES(#{p.user},#{p.role})","user",user,"role",first);
        db.execute("INSERT INTO user_role VALUES(#{p.user},#{p.role})","user",user,"role",second);
        db.execute("INSERT INTO role_permission VALUES(#{p.role},2)","role",first);
        db.execute("INSERT INTO role_permission VALUES(#{p.role},2)","role",second);
        assertEquals(Set.of("platform:access","accounts:read"),permissions.effective(user));
        Session reader = login(prefix+"_u"), admin = admin();
        http.perform(requestPut("/api/roles/"+first+"/permissions",admin,Map.of("version",0,"ids",List.of()))).andExpect(status().isOk());
        http.perform(get("/api/accounts").cookie(reader.cookie())).andExpect(status().isOk());
        http.perform(requestPut("/api/roles/"+second+"/permissions",admin,Map.of("version",0,"ids",List.of()))).andExpect(status().isOk());
        http.perform(get("/api/accounts").cookie(reader.cookie())).andExpect(status().isForbidden());
    }
    @Test void invalidGrantRollsBackVersionAndRelationships() {
        long target=role("_rollback");
        db.execute("INSERT INTO role_permission VALUES(#{p.role},13)","role",target);
        assertThrows(Problem.class,()->management.replaceGrants(adminId(),false,target,Map.of("version",0,"ids",List.of(99999999))));
        assertEquals(0L,db.number("SELECT grants_version FROM role WHERE id=#{p.id}","id",target));
        assertEquals(1L,db.number("SELECT COUNT(*) FROM role_permission WHERE role_id=#{p.id}","id",target));
    }
    @Test void lastAdministratorCannotBeDisabledAndChangesRollBack() throws Exception {
        Session admin=admin(); long id=adminId(); long version=db.number("SELECT version FROM account WHERE id=#{p.id}","id",id);
        http.perform(requestPut("/api/accounts/"+id,admin,Map.of("displayName","开发管理员","enabled",false,"locked",false,"version",version))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_ADMINISTRATOR"));
        assertEquals(Boolean.TRUE,db.one("SELECT enabled FROM account WHERE id=#{p.id}","id",id).get("enabled"));
        http.perform(get("/api/me").cookie(admin.cookie())).andExpect(status().isOk());
    }
    @Test void lastAdministratorPermissionsCannotBeReplacedWithEmptySet() throws Exception {
        long version=db.number("SELECT grants_version FROM role WHERE id=1"); Session admin=admin();
        http.perform(requestPut("/api/roles/1/permissions",admin,Map.of("version",version,"ids",List.of()))).andExpect(status().isConflict());
        assertTrue(permissions.effective(adminId()).containsAll(PermissionProcesses.MANAGEMENT));
        assertEquals(version,db.number("SELECT grants_version FROM role WHERE id=1"));
    }
    @Test void concurrentWritesCannotSilentlyOverwrite() throws Exception {
        long target=role("_concurrent"), actor=adminId(); CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Integer> write=()->{ start.await(); try { management.saveRole(actor,target,Map.of("name","并发修改","description","","enabled",true,"version",0)); return 200; } catch(Problem problem){return problem.status;} };
            Future<Integer> first=pool.submit(write), second=pool.submit(write); start.countDown();
            assertEquals(Set.of(200,409),Set.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS)));
        }
        assertEquals(1L,db.number("SELECT version FROM role WHERE id=#{p.id}","id",target));
    }
    @Test void employeeCannotBeAssignedToTwoAccounts() throws Exception {
        long staff=db.insert("INSERT INTO employee(source_key,full_name,organization_id,position_name,synthetic) VALUES(#{p.key},'合成关联员工',1,'测试岗位',1)",Store.parameters("key",prefix));
        management.createAccount(adminId(),Map.of("username",prefix+"_first","displayName","合成管理账号","employeeId",staff,"password",PASSWORD));
        Session admin=admin();
        http.perform(post("/api/accounts").cookie(admin.cookie()).header("X-CSRF-Token",admin.csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("username",prefix,"displayName","合成账号","employeeId",staff,"password",PASSWORD)))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMPLOYEE_ALREADY_LINKED"));
    }
    @Test void managementRoleCannotReceiveBusinessPermission() {
        long role=role("_boundary");
        assertThrows(Problem.class,()->management.replaceGrants(adminId(),false,role,Map.of("version",0,"ids",List.of(13))));
        assertEquals(0L,db.number("SELECT grants_version FROM role WHERE id=#{p.id}","id",role));
    }
    @Test void sessionExpiryIsAbsoluteAtExactly1800Seconds() throws Exception {
        user("_clock"); Session session=login(prefix+"_clock"); Instant start=clock.instant();
        clock.value=start.plusSeconds(1799);
        http.perform(get("/api/me").cookie(session.cookie())).andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"));
        clock.value=start.plusSeconds(1800);
        http.perform(get("/api/me").cookie(session.cookie())).andExpect(status().isUnauthorized());
        clock.value=start.plusSeconds(1801);
        http.perform(get("/api/me").cookie(session.cookie())).andExpect(status().isUnauthorized());
    }
    @Test void logoutInvalidatesTheDatabaseSession() throws Exception {
        user("_logout"); Session session=login(prefix+"_logout");
        http.perform(post("/api/session/logout").cookie(session.cookie()).header("X-CSRF-Token",session.csrf())).andExpect(status().isNoContent());
        http.perform(get("/api/me").cookie(session.cookie())).andExpect(status().isUnauthorized());
    }
    @Test void loginAndMeReturnTheSameExpiryAtDatabasePrecision() throws Exception {
        clock.value=Instant.parse("2026-10-20T00:00:00.123456789Z");
        user("_precision"); Session session=login(prefix+"_precision");
        assertEquals(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS).plusSeconds(1800),session.expiresAt());
        clock.value=session.expiresAt().minusNanos(1);
        http.perform(get("/api/me").cookie(session.cookie())).andExpect(status().isOk())
            .andExpect(jsonPath("$.expiresAt").value(session.expiresAt().toString()));
        clock.value=session.expiresAt();
        http.perform(get("/api/me").cookie(session.cookie())).andExpect(status().isUnauthorized());
    }
    @Test void queryParametersAreBoundRatherThanConcatenated() throws Exception {
        Session admin=admin();
        http.perform(get("/api/accounts").param("q","' OR 1=1 --").cookie(admin.cookie())).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }
    @Test void employeeLookupMasksSourceNamesAndReportsExistingAssociation() throws Exception {
        Session admin=admin();
        long employee=db.insert("INSERT INTO employee(source_key,full_name,organization_id,position_name,synthetic) VALUES(#{p.key},'PRIVATE_SOURCE_SENTINEL',1,'合成岗位',0)",Store.parameters("key",prefix+"_employee"));
        http.perform(get("/api/employees").cookie(admin.cookie()).param("q","#"+employee))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].label").value("员工 #"+employee))
            .andExpect(jsonPath("$.items[0].fullName").doesNotExist());
        http.perform(get("/api/employees").cookie(admin.cookie()).param("q","PRIVATE_SOURCE_SENTINEL"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        http.perform(get("/api/employees").cookie(admin.cookie()).param("q","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
            ;
        http.perform(get("/api/employees").cookie(admin.cookie()).param("q","' OR 1=1 --"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }
    @Test void employeeLookupSupportsPaginationAndRejectsOversizedSearch() throws Exception {
        Session admin=admin();
        http.perform(get("/api/employees").cookie(admin.cookie()).param("page","2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(2)).andExpect(jsonPath("$.pageSize").value(100));
        http.perform(get("/api/employees").cookie(admin.cookie()).param("q","x".repeat(81)))
            .andExpect(status().isBadRequest());
    }
}
