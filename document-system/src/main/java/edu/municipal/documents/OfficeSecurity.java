// Local synthetic identity sessions only; accepts fixed configured profiles, never arbitrary employee IDs.
// Separate OFFICE_SESSION and CSRF guard prevent sharing administrator identity with the business app.
package edu.municipal.documents;
import jakarta.servlet.http.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class OfficeSecurity implements WebMvcConfigurer {
    private final long editor,reader;
    public OfficeSecurity(@Value("${office.mock.editor-id}") long editor,@Value("${office.mock.reader-id}") long reader) {
        if(editor<=0 || reader<=0 || editor==reader) throw new IllegalStateException("Configure the two synthetic employee profiles before starting");
        this.editor=editor;this.reader=reader;
    }
    public List<Map<String,Object>> profiles() {
        return List.of(Map.of("profile","editor","employeeId",editor,"name","公文编辑员工（合成）"),Map.of("profile","reader","employeeId",reader,"name","公文阅读员工（合成）"));
    }
    public Map<String,Object> login(Map<String,Object> body,HttpServletRequest request) {
        if(body.size()!=1 || !Set.of("editor","reader").contains(body.get("profile"))) throw new OfficeProblem(400,"INVALID_PROFILE","请选择预设合成员工");
        String profile=(String)body.get("profile");long employee=profile.equals("editor")?editor:reader;
        if(employee<=0) throw new OfficeProblem(503,"NOT_CONFIGURED","本机对接尚未配置");
        if(request.getSession(false)!=null) request.getSession(false).invalidate();
        HttpSession session=request.getSession(true);
        session.setAttribute("employee",employee);session.setAttribute("profile",profile);
        session.setAttribute("csrf",UUID.randomUUID().toString());session.setAttribute("expires",Instant.now().plusSeconds(1800));
        return me(request);
    }
    public Map<String,Object> me(HttpServletRequest request) {
        HttpSession session=identity(request);
        return Map.of("employeeId",session.getAttribute("employee"),"profile",session.getAttribute("profile"),"csrfToken",session.getAttribute("csrf"),"expiresAt",session.getAttribute("expires"));
    }
    public long employee(HttpServletRequest request) {return (long)identity(request).getAttribute("employee");}
    private HttpSession identity(HttpServletRequest request) {
        HttpSession session=request.getSession(false);
        if(session==null || session.getAttribute("employee")==null || !Instant.now().isBefore((Instant)session.getAttribute("expires"))) {
            if(session!=null)session.invalidate();throw new OfficeProblem(401,"UNAUTHENTICATED","请选择合成员工建立本机验证会话");
        }
        return session;
    }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
                response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("X-Frame-Options","DENY");
                String origin=request.getHeader("Origin");
                if(origin!=null && !origin.matches("http://(localhost|127\\.0\\.0\\.1):9090")) throw new OfficeProblem(403,"ORIGIN_REJECTED","请求来源不受信任");
                if(Set.of("/office-api/health","/office-api/profiles").contains(request.getRequestURI()) && request.getMethod().equals("GET")) return true;
                if(request.getRequestURI().equals("/office-api/mock-session") && request.getMethod().equals("POST")) {
                    if(request.getContentType()==null || !request.getContentType().startsWith("application/json"))throw new OfficeProblem(400,"INVALID_INPUT","请使用 JSON");return true;
                }
                HttpSession session=identity(request);
                if(!Set.of("GET","HEAD").contains(request.getMethod())) {
                    String csrf=request.getHeader("X-CSRF-Token");
                    if(csrf==null || !MessageDigest.isEqual(csrf.getBytes(StandardCharsets.UTF_8),session.getAttribute("csrf").toString().getBytes(StandardCharsets.UTF_8))) throw new OfficeProblem(403,"CSRF_REJECTED","请求校验失败，请重新建立会话");
                }
                return true;
            }
        }).addPathPatterns("/office-api/**");
    }
}
