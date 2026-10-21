// HTTP identity/CSRF boundary. Every API except health/login requires a database-backed session.

package edu.municipal.access;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class SecurityGate implements WebMvcConfigurer {
    public static final String COOKIE = "MUNI_SESSION";
    private final SessionProcesses sessions;
    public SecurityGate(SessionProcesses sessions) { this.sessions = sessions; }
    public static SessionProcesses.Identity identity(HttpServletRequest request) { return (SessionProcesses.Identity) request.getAttribute("identity"); }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                response.setHeader("Cache-Control", "no-store");
                response.setHeader("X-Content-Type-Options", "nosniff");
                response.setHeader("X-Frame-Options", "DENY");
                if (request.getRequestURI().equals("/api/health") && request.getMethod().equals("GET")) return true;
                String origin = request.getHeader("Origin");
                if (origin != null && !origin.matches("http://(localhost|127\\.0\\.0\\.1):(8080|5173|4173)")) throw new Problem(403, "ORIGIN_REJECTED", "请求来源不受信任");
                if (request.getRequestURI().equals("/api/session/login") && request.getMethod().equals("POST")) {
                    if (request.getContentType() == null || !request.getContentType().startsWith("application/json")) throw Problem.bad("登录请求必须使用 JSON");
                    return true;
                }
                String token = null;
                if (request.getCookies() != null) for (Cookie cookie : request.getCookies()) if (cookie.getName().equals(COOKIE)) token = cookie.getValue();
                SessionProcesses.Identity identity = sessions.authenticate(token);
                request.setAttribute("identity", identity);
                if (!Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())) {
                    String supplied = request.getHeader("X-CSRF-Token");
                    if (supplied == null || !MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8), identity.csrfToken().getBytes(StandardCharsets.UTF_8))) throw new Problem(403, "CSRF_REJECTED", "请求校验已失效，请刷新页面后重试");
                }
                return true;
            }
        }).addPathPatterns("/api/**");
    }
}
