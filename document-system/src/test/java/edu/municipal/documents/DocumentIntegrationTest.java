// Independent H2 business integration tests with a controllable HTTP RBAC stub.
// Verifies that malformed/denied/unavailable decisions never change documents or their audit records.
package edu.municipal.documents;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:office-test;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","office.mock.editor-id=11","office.mock.reader-id=12","office.app-key=test-only"})
@AutoConfigureMockMvc
class DocumentIntegrationTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final AtomicReference<String> mode=new AtomicReference<>("allow");
    static final AtomicInteger calls=new AtomicInteger();
    static final HttpServer server=start();
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    MockHttpSession session;String csrf;
    static HttpServer start() {
        try {
            HttpServer endpoint=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            endpoint.createContext("/check",exchange->{
                calls.incrementAndGet();JsonNode request=JSON.readTree(exchange.getRequestBody());String state=mode.get();
                if(state.equals("timeout")) try {Thread.sleep(1800);}catch(InterruptedException failure){Thread.currentThread().interrupt();}
                List<Map<String,Object>> results=new ArrayList<>();
                for(JsonNode code:request.path("permissionCodes"))results.add(Map.of("permissionCode",code.asText(),"allowed",!state.equals("deny"),"reason",state.equals("deny")?"NO_PERMISSION":"ALLOWED"));
                if(state.equals("missing"))results.clear();
                if(state.equals("duplicate") && !results.isEmpty())results.add(results.getFirst());
                byte[] bytes=JSON.writeValueAsBytes(Map.of("employeeId",state.equals("wrong-employee")?999:request.path("employeeId").asLong(),"results",results,"requestId","0123456789abcdef0123456789abcdef"));
                try {exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(state.equals("error")?500:200,bytes.length);exchange.getResponseBody().write(bytes);}catch(java.io.IOException ignored){}finally{exchange.close();}
            });endpoint.start();return endpoint;
        }catch(Exception error){throw new ExceptionInInitializerError(error);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {registry.add("office.rbac-url",()->"http://127.0.0.1:"+server.getAddress().getPort()+"/check");}
    @AfterAll static void stop(){server.stop(0);}
    @BeforeEach void prepare() throws Exception {
        mode.set("allow");calls.set(0);
        var login=http.perform(post("/office-api/mock-session").contentType("application/json").content("{\"profile\":\"editor\"}")).andExpect(status().isOk()).andReturn();
        session=(MockHttpSession)login.getRequest().getSession(false);csrf=json.readTree(login.getResponse().getContentAsString()).path("csrfToken").asText();
    }
    private Map<String,Object> payload(){return Map.of("title","合成公文更新","content","经过权限校验后的合成正文","version",db.queryForObject("SELECT version FROM office_document WHERE id='DOC-001'",Long.class));}
    private void update(int expected) throws Exception {http.perform(put("/office-api/documents/DOC-001").session(session).header("X-CSRF-Token",csrf).contentType("application/json").content(json.writeValueAsString(payload()))).andExpect(status().is(expected));}
    @Test void allowedWriteCommitsAndEveryOperationChecksAgain() throws Exception {
        long before=db.queryForObject("SELECT version FROM office_document WHERE id='DOC-001'",Long.class);
        http.perform(get("/office-api/documents").session(session)).andExpect(status().isOk());update(200);
        assertEquals(before+1,db.queryForObject("SELECT version FROM office_document WHERE id='DOC-001'",Long.class));assertEquals(2,calls.get());
    }
    @Test void revokeAfterPageLoadRefusesWriteWithoutChangingVersionOrAudit() throws Exception {
        http.perform(get("/office-api/documents").session(session)).andExpect(status().isOk());
        var before=db.queryForMap("SELECT * FROM office_document WHERE id='DOC-001'");long logs=db.queryForObject("SELECT COUNT(*) FROM office_change",Long.class);
        mode.set("deny");update(403);
        assertEquals(before,db.queryForMap("SELECT * FROM office_document WHERE id='DOC-001'"));assertEquals(logs,db.queryForObject("SELECT COUNT(*) FROM office_change",Long.class));
    }
    @Test void missingDuplicateWrongIdentityAndServerErrorFailClosed() throws Exception {
        var before=db.queryForMap("SELECT * FROM office_document WHERE id='DOC-001'");
        for(String state:List.of("missing","duplicate","wrong-employee","error")){mode.set(state);update(503);assertEquals(before,db.queryForMap("SELECT * FROM office_document WHERE id='DOC-001'"));}
    }
    @Test void timeoutDoesNotWriteOrRetry() throws Exception {
        var before=db.queryForMap("SELECT * FROM office_document WHERE id='DOC-001'");mode.set("timeout");update(503);
        assertEquals(1,calls.get());assertEquals(before,db.queryForMap("SELECT * FROM office_document WHERE id='DOC-001'"));
    }
    @Test void businessVersionConflictAndCsrfAreEnforced() throws Exception {
        var body=new HashMap<>(payload());body.put("version",999999);
        http.perform(put("/office-api/documents/DOC-001").session(session).header("X-CSRF-Token",csrf).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isConflict());
        http.perform(put("/office-api/documents/DOC-001").session(session).contentType("application/json").content(json.writeValueAsString(payload()))).andExpect(status().isForbidden());
    }
    @Test void arbitraryEmployeeAndForeignOriginCannotCreateBusinessIdentity() throws Exception {
        http.perform(post("/office-api/mock-session").contentType("application/json").content("{\"profile\":\"editor\",\"employeeId\":999}")).andExpect(status().isBadRequest());
        http.perform(post("/office-api/mock-session").header("Origin","https://untrusted.example").contentType("application/json").content("{\"profile\":\"editor\"}")).andExpect(status().isForbidden());
        http.perform(get("/office-api/documents").header("X-Employee-Id","11")).andExpect(status().isUnauthorized());
    }
}
