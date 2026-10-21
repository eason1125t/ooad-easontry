// Thin HTTP adapter for functional management processes; each operation checks its own permission.

package edu.municipal.access;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ManagementController {
    private final ManagementProcesses processes;
    private final PermissionProcesses permissions;
    private final Store db;
    public ManagementController(ManagementProcesses processes, PermissionProcesses permissions, Store db) { this.processes = processes; this.permissions = permissions; this.db = db; }
    private long actor(HttpServletRequest request) { return SecurityGate.identity(request).accountId(); }
    private void check(HttpServletRequest request, String permission) { permissions.require(actor(request), permission); }
    @GetMapping("/overview") public Object overview(HttpServletRequest request) {
        check(request, "platform:access");
        return Map.of("accounts", db.number("SELECT COUNT(*) FROM account"), "roles", db.number("SELECT COUNT(*) FROM role"), "resources", db.number("SELECT COUNT(*) FROM resource"), "permissions", db.number("SELECT COUNT(*) FROM permission"));
    }
    @GetMapping("/accounts") public Object accounts(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int pageSize, @RequestParam(defaultValue="") String q, HttpServletRequest request) { check(request, "accounts:read"); return processes.accounts(page, pageSize, q); }
    @PostMapping("/accounts") public Object createAccount(@RequestBody Map<String,Object> body, HttpServletRequest request) { return Map.of("id", processes.createAccount(actor(request), body)); }
    @PutMapping("/accounts/{id}") public Object account(@PathVariable long id, @RequestBody Map<String,Object> body, HttpServletRequest request) { processes.updateAccount(actor(request), id, body); return Map.of("saved", true); }
    @PutMapping("/accounts/{id}/password") public Object password(@PathVariable long id, @RequestBody Map<String,Object> body, HttpServletRequest request) { processes.resetPassword(actor(request), id, body); return Map.of("saved", true); }
    @GetMapping("/roles") public Object roles(HttpServletRequest request) { check(request,"roles:read"); return processes.roles(); }
    @PostMapping("/roles") public Object createRole(@RequestBody Map<String,Object> body,HttpServletRequest request) { return Map.of("id", processes.saveRole(actor(request),null,body)); }
    @PutMapping("/roles/{id}") public Object role(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest request) { processes.saveRole(actor(request),id,body); return Map.of("saved",true); }
    @GetMapping("/resources") public Object resources(HttpServletRequest request) { check(request,"resources:read"); return processes.resources(); }
    @PostMapping("/resources") public Object createResource(@RequestBody Map<String,Object> body,HttpServletRequest request) { return Map.of("id",processes.saveResource(actor(request),null,body)); }
    @PutMapping("/resources/{id}") public Object resource(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest request) { processes.saveResource(actor(request),id,body); return Map.of("saved",true); }
    @GetMapping("/permissions") public Object permissionList(HttpServletRequest request) { check(request,"permissions:read"); return processes.permissionList(); }
    @PostMapping("/permissions") public Object createPermission(@RequestBody Map<String,Object> body,HttpServletRequest request) { return Map.of("id",processes.savePermission(actor(request),null,body)); }
    @PutMapping("/permissions/{id}") public Object permission(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest request) { processes.savePermission(actor(request),id,body); return Map.of("saved",true); }
    @GetMapping("/accounts/{id}/roles") public Object userRoles(@PathVariable long id,HttpServletRequest request) { check(request,"grants:read"); return processes.grants(true,id); }
    @PutMapping("/accounts/{id}/roles") public Object userRoles(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest request) { processes.replaceGrants(actor(request),true,id,body); return Map.of("saved",true); }
    @GetMapping("/roles/{id}/permissions") public Object rolePermissions(@PathVariable long id,HttpServletRequest request) { check(request,"grants:read"); return processes.grants(false,id); }
    @PutMapping("/roles/{id}/permissions") public Object rolePermissions(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest request) { processes.replaceGrants(actor(request),false,id,body); return Map.of("saved",true); }
    @GetMapping("/accounts/{id}/permissions") public Object effective(@PathVariable long id,HttpServletRequest request) { check(request,"grants:read"); db.one("SELECT id FROM account WHERE id=#{p.id}","id",id); return Map.of("permissions",permissions.effective(id)); }
    @GetMapping("/employees") public Object employees(@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="") String q,HttpServletRequest request) {
        check(request,"employees:read"); return processes.employees(page, q);
    }
    @GetMapping("/organizations") public Object organizations(HttpServletRequest request) { check(request,"employees:read"); return db.list("SELECT id,name,parent_id FROM organization ORDER BY id"); }
}
