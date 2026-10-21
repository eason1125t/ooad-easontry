// Single external batch endpoint. SecurityGate isolates it from administrator sessions.
package edu.municipal.access;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
public class AuthorizationController {
    private final AuthorizationProcesses authorization;
    public AuthorizationController(AuthorizationProcesses authorization) { this.authorization=authorization; }
    @PostMapping("/api/authorization/check")
    public Object check(@RequestBody JsonNode body,HttpServletRequest request) {
        return authorization.check(request.getHeader("X-App-Key"),body,(String)request.getAttribute("authorizationRequestId"));
    }
}
