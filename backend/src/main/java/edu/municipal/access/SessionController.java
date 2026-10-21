// Authentication HTTP adapter. Cookie duration is set only on login, never on normal requests.

package edu.municipal.access;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class SessionController {
    private final SessionProcesses sessions;
    private final Store db;
    private final boolean secure;
    private final int seconds;
    public SessionController(SessionProcesses sessions, Store db, @Value("${app.cookie-secure:false}") boolean secure, @Value("${app.session-seconds:1800}") int seconds) {
        this.sessions = sessions; this.db = db; this.secure = secure; this.seconds = seconds;
    }
    @GetMapping("/health") public Object health() { db.number("SELECT 1"); return Map.of("status", "UP"); }
    @PostMapping("/session/login") public ResponseEntity<?> login(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        SessionProcesses.Login login = sessions.login(body, request.getRemoteAddr());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie(login.token(), seconds).toString()).body(sessions.me(login.identity()));
    }
    @GetMapping("/me") public Object me(HttpServletRequest request) { return sessions.me(SecurityGate.identity(request)); }
    @PostMapping("/session/logout") public ResponseEntity<?> logout(HttpServletRequest request) {
        sessions.logout(SecurityGate.identity(request));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", 0).toString()).build();
    }
    private ResponseCookie cookie(String token, int lifetime) {
        return ResponseCookie.from(SecurityGate.COOKIE, token).httpOnly(true).secure(secure).sameSite("Strict").path("/api").maxAge(Duration.ofSeconds(lifetime)).build();
    }
}
