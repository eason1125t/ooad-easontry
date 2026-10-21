// Administrator-only employee grant and status adapters; application keys cannot access these paths.
package edu.municipal.access;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/employees/{id}")
public class EmployeeController {
    private final EmployeeProcesses employees;
    private final PermissionProcesses permissions;
    public EmployeeController(EmployeeProcesses employees,PermissionProcesses permissions) { this.employees=employees;this.permissions=permissions; }
    private long actor(HttpServletRequest request) { return SecurityGate.identity(request).accountId(); }
    @GetMapping("/roles") public Object roles(@PathVariable long id,HttpServletRequest request) { permissions.require(actor(request),"grants:read");return employees.grants(id); }
    @PutMapping("/roles") public Object roles(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest request) { employees.replace(actor(request),id,body);return Map.of("saved",true); }
    @GetMapping("/permissions") public Object effective(@PathVariable long id,HttpServletRequest request) { permissions.require(actor(request),"grants:read");return employees.effective(id); }
    @PutMapping public Object status(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest request) { employees.update(actor(request),id,body);return Map.of("saved",true); }
}
