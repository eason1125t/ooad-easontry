// One remote batch per business check. Strictly validates identity and complete decision coverage.
// The business controller supplies fixed permissions and a session employee; no browser-controlled roles.
package edu.municipal.documents;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RbacClient {
    public record Decisions(Map<String,Boolean> values,String requestId) {}
    private final ObjectMapper json;
    private final URI endpoint;
    private final String key;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).followRedirects(HttpClient.Redirect.NEVER).build();
    public RbacClient(ObjectMapper json,@Value("${office.rbac-url}") String url,@Value("${office.app-key}") String key) {
        if(key.isBlank()) throw new IllegalStateException("Configure the private application credential before starting");
        this.json=json;this.endpoint=URI.create(url);this.key=key;
    }
    public Decisions check(long employee,List<String> codes) {
        try {
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofMillis(1500)).header("Content-Type","application/json").header("X-App-Key",key)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("employeeId",employee,"permissionCodes",codes)))).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200) throw unavailable();
            JsonNode body=json.readTree(response.body());
            if(!body.path("employeeId").isIntegralNumber() || !body.path("employeeId").canConvertToLong() || body.path("employeeId").asLong()!=employee || !body.path("results").isArray()
                || body.path("results").size()!=codes.size() || !body.path("requestId").isTextual() || !body.path("requestId").asText().matches("[a-f0-9]{32}")) throw unavailable();
            Map<String,Boolean> values=new LinkedHashMap<>();
            for(int index=0;index<codes.size();index++) {
                JsonNode item=body.path("results").get(index);String code=item.path("permissionCode").asText(),reason=item.path("reason").asText();
                if(!codes.get(index).equals(code) || !item.path("allowed").isBoolean() || !Set.of("ALLOWED","NO_PERMISSION","EMPLOYEE_UNAVAILABLE").contains(reason)
                    || item.path("allowed").asBoolean()!=reason.equals("ALLOWED") || values.containsKey(code)) throw unavailable();
                values.put(code,item.path("allowed").asBoolean());
            }
            return new Decisions(Map.copyOf(values),body.path("requestId").asText());
        } catch(InterruptedException failure) { Thread.currentThread().interrupt();throw unavailable(); }
        catch(OfficeProblem failure) {throw failure;}
        catch(Exception failure) {throw unavailable();}
    }
    public String require(long employee,String permission) {
        Decisions result=check(employee,List.of(permission));
        if(!Boolean.TRUE.equals(result.values().get(permission))) throw new OfficeProblem(403,"FORBIDDEN","权限不足，操作未执行");
        return result.requestId();
    }
    private OfficeProblem unavailable() {return new OfficeProblem(503,"AUTHORIZATION_UNAVAILABLE","鉴权服务暂不可用，操作未执行");}
}
