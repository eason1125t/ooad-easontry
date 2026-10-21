// Real MySQL batch authorization tests. Reuses the isolated test database and clock fixtures.
// Exercises the public HTTP boundary, employee grants and a concurrent snapshot transition.
package edu.municipal.access;

import com.fasterxml.jackson.databind.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(RbacIntegrationTest.TestTime.class)
class AuthorizationIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) throws Exception { RbacIntegrationTest.testDatabase(registry); }
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @MockitoSpyBean Store db;
    @Autowired EmployeeProcesses employees;
    @Autowired ManagementProcesses management;
    @Autowired RbacIntegrationTest.MutableClock clock;
    long staff,role,client,admin;
    String key,keyId,secret;
    static final String READ="office.documents:read", UPDATE="office.documents:update";
    @BeforeEach void prepare() {
        clock.value=Instant.parse("2026-10-21T00:00:00Z");
        String unique=UUID.randomUUID().toString().replace("-","");
        staff=db.insert("INSERT INTO employee(source_key,full_name,organization_id,position_name,synthetic) VALUES(#{p.key},'批量测试员工',1,'测试岗位',1)",Store.parameters("key","test:"+unique));
        role=db.insert("INSERT INTO role(code,name,purpose) VALUES(#{p.code},'批量测试角色','BUSINESS')",Store.parameters("code","batch_"+unique));
        db.execute("INSERT INTO employee_role VALUES(#{p.e},#{p.r})","e",staff,"r",role);
        db.execute("INSERT INTO role_permission SELECT #{p.r},id FROM permission WHERE code=#{p.code}","r",role,"code",READ);
        client=db.insert("INSERT INTO integration_client(code,application_id) SELECT #{p.code},id FROM resource WHERE code='office'",Store.parameters("code","test_"+unique));
        keyId=unique; secret=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);key=keyId+"."+secret;
        db.execute("INSERT INTO integration_credential(id,client_id,secret_hash) VALUES(#{p.id},#{p.client},#{p.hash})","id",keyId,"client",client,"hash",SessionProcesses.hash(secret));
        admin=db.number("SELECT id FROM account WHERE username='project_admin'");
    }
    private JsonNode check(List<String> codes) throws Exception {
        var result=http.perform(post("/api/authorization/check").header("X-App-Key",key).contentType("application/json").content(json.writeValueAsString(Map.of("employeeId",staff,"permissionCodes",codes)))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }
    @Test void mixedResultsDoNotRequireAnEmployeeAccount() throws Exception {
        var result=check(List.of(READ,UPDATE));
        assertTrue(result.at("/results/0/allowed").asBoolean());assertFalse(result.at("/results/1/allowed").asBoolean());
        assertEquals(READ,result.at("/results/0/permissionCode").asText());
        assertEquals(0,db.number("SELECT COUNT(*) FROM account WHERE employee_id=#{p.id}","id",staff));
    }
    @Test void unionRevocationAndRestoreAffectFollowingRequests() throws Exception {
        long second=db.insert("INSERT INTO role(code,name,purpose) VALUES(#{p.code},'第二路径','BUSINESS')",Store.parameters("code","second_"+keyId));
        db.execute("INSERT INTO role_permission SELECT #{p.r},id FROM permission WHERE code=#{p.code}","r",second,"code",READ);
        db.execute("INSERT INTO employee_role VALUES(#{p.e},#{p.r})","e",staff,"r",second);
        employees.replace(admin,staff,Map.of("version",0,"ids",List.of(second)));
        assertTrue(check(List.of(READ)).at("/results/0/allowed").asBoolean());
        employees.replace(admin,staff,Map.of("version",1,"ids",List.of()));
        assertFalse(check(List.of(READ)).at("/results/0/allowed").asBoolean());
        employees.replace(admin,staff,Map.of("version",2,"ids",List.of(role)));
        assertTrue(check(List.of(READ)).at("/results/0/allowed").asBoolean());
    }
    @Test void batchSizesOneAndFiftyWorkWhileInvalidInputsFailTogether() throws Exception {
        check(List.of(READ));List<String> fifty=new ArrayList<>();
        for(int i=0;i<50;i++) fifty.add("office.unknown"+i+":read");
        assertEquals(50,check(fifty).path("results").size());
        var requests=List.of("{\"employeeId\":"+staff+",\"permissionCodes\":[]}",
            json.writeValueAsString(Map.of("employeeId",staff,"permissionCodes",Collections.nCopies(51,READ))),
            json.writeValueAsString(Map.of("employeeId",staff,"permissionCodes",List.of(READ,READ))),
            "{\"employeeId\":"+staff+",\"permissionCodes\":[null]}",
            "{\"employeeId\":"+staff+",\"permissionCodes\":[\"*\"]}",
            "{\"employeeId\":"+staff+",\"permissionCodes\":[\" office.documents:read\"]}",
            "{\"employeeId\":"+staff+",\"permissionCode\":\"office.documents:read\"}",
            "{\"employeeId\":1.5,\"permissionCodes\":[\"office.documents:read\"]}");
        for(String body:requests) http.perform(post("/api/authorization/check").header("X-App-Key",key).contentType("application/json").content(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CHECK_REQUEST")).andExpect(jsonPath("$.results").doesNotExist()).andExpect(jsonPath("$.requestId").exists());
    }
    @Test void mixedForeignOrManagementScopeFailsWholeBatch() throws Exception {
        for(String foreign:List.of("accounts:read","demo.documents:read")) http.perform(post("/api/authorization/check").header("X-App-Key",key).contentType("application/json").content(json.writeValueAsString(Map.of("employeeId",staff,"permissionCodes",List.of(READ,foreign))))).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("APPLICATION_SCOPE_DENIED")).andExpect(jsonPath("$.results").doesNotExist());
    }
    @Test void credentialsCannotBeSubstitutedByCookiesOrSpoofedHeaders() throws Exception {
        http.perform(post("/api/authorization/check").header("X-Employee-Id",staff).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        http.perform(get("/api/accounts").header("X-App-Key",key)).andExpect(status().isUnauthorized());
        db.execute("UPDATE integration_credential SET enabled=0 WHERE id=#{p.id}","id",keyId);
        http.perform(post("/api/authorization/check").header("X-App-Key",key).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
    }
    @Test void unavailableEmployeesHaveNoExistenceOracle() throws Exception {
        employees.update(admin,staff,Map.of("enabled",false,"version",0));
        assertEquals("EMPLOYEE_UNAVAILABLE",check(List.of(READ)).at("/results/0/reason").asText());
        staff=Long.MAX_VALUE;
        assertEquals("EMPLOYEE_UNAVAILABLE",check(List.of(READ)).at("/results/0/reason").asText());
    }
    @Test void expiredKeyAndDisabledApplicationAreRejected() throws Exception {
        db.execute("UPDATE integration_credential SET expires_at=#{p.time} WHERE id=#{p.id}","id",keyId,"time",Timestamp.from(clock.instant()));
        http.perform(post("/api/authorization/check").header("X-App-Key",key).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        db.execute("UPDATE integration_credential SET expires_at=NULL WHERE id=#{p.id}","id",keyId);
        long client=db.number("SELECT client_id FROM integration_credential WHERE id=#{p.id}","id",keyId);
        db.execute("UPDATE integration_client SET enabled=0 WHERE id=#{p.id}","id",client);
        http.perform(post("/api/authorization/check").header("X-App-Key",key).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        db.execute("UPDATE integration_client SET enabled=1 WHERE id=#{p.id}","id",client);
        long application=db.number("SELECT application_id FROM integration_client WHERE id=#{p.id}","id",client);
        try {
            db.execute("UPDATE resource SET enabled=0 WHERE id=#{p.id}","id",application);
            http.perform(post("/api/authorization/check").header("X-App-Key",key).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        } finally { db.execute("UPDATE resource SET enabled=1 WHERE id=#{p.id}","id",application); }
    }
    @Test void businessAndManagementGrantBoundariesRollback() {
        assertThrows(Problem.class,()->employees.replace(admin,staff,Map.of("version",0,"ids",List.of(1))));
        assertThrows(Problem.class,()->management.replaceGrants(admin,false,role,Map.of("version",0,"ids",List.of(1))));
        assertEquals(0,db.number("SELECT grants_version FROM employee WHERE id=#{p.id}","id",staff));
        assertThrows(Problem.class,()->employees.replace(admin,staff,Map.of("version",9,"ids",List.of())));
    }
    @Test void roleAndResourceStateAffectDecisions() throws Exception {
        db.execute("UPDATE role SET enabled=0 WHERE id=#{p.id}","id",role);
        assertFalse(check(List.of(READ)).at("/results/0/allowed").asBoolean());
        db.execute("UPDATE role SET enabled=1 WHERE id=#{p.id}","id",role);
        long module=db.number("SELECT id FROM resource WHERE code='office.work'");
        try { db.execute("UPDATE resource SET enabled=0 WHERE id=#{p.id}","id",module);assertFalse(check(List.of(READ)).at("/results/0/allowed").asBoolean()); }
        finally { db.execute("UPDATE resource SET enabled=1 WHERE id=#{p.id}","id",module); }
    }
    @Test void batchRetainsOneSnapshotWhileFollowingBatchSeesCommittedRevocation() throws Exception {
        CountDownLatch read=new CountDownLatch(1),resume=new CountDownLatch(1);AtomicBoolean once=new AtomicBoolean();
        doAnswer(invocation->{Object result=invocation.callRealMethod();String sql=invocation.getArgument(0);
            if(sql.startsWith("SELECT k.secret_hash") && once.compareAndSet(false,true)) {read.countDown();assertTrue(resume.await(10,TimeUnit.SECONDS));}
            return result;}).when(db).list(anyString(),any(Object[].class));
        try(var pool=Executors.newSingleThreadExecutor()) {
            Future<JsonNode> pending=pool.submit(()->check(List.of(READ,UPDATE)));
            assertTrue(read.await(10,TimeUnit.SECONDS));
            employees.replace(admin,staff,Map.of("version",0,"ids",List.of()));resume.countDown();
            assertTrue(pending.get(10,TimeUnit.SECONDS).at("/results/0/allowed").asBoolean());
            assertFalse(check(List.of(READ)).at("/results/0/allowed").asBoolean());
        } finally {resume.countDown();reset(db);}
    }
}
