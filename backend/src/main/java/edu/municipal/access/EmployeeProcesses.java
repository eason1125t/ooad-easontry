// Employee business grants and status updates reuse the serialized management transaction.
// Management roles remain account-only; AuthorizationProcesses consumes employee relationships.
package edu.municipal.access;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class EmployeeProcesses {
    private final Store db;
    private final ManagementProcesses management;
    private final AuthorizationProcesses authorization;
    public EmployeeProcesses(Store db,ManagementProcesses management,AuthorizationProcesses authorization) {
        this.db=db; this.management=management; this.authorization=authorization;
    }
    public Map<String,Object> grants(long id) {
        var staff=db.one("SELECT grants_version FROM employee WHERE id=#{p.id}","id",id);
        return Map.of("version",staff.get("grantsVersion"),"ids",db.list("SELECT role_id AS id FROM employee_role WHERE employee_id=#{p.id}","id",id).stream().map(row->row.get("id")).toList());
    }
    public void replace(long actor,long id,Map<String,Object> body) {
        management.change(actor,"employee.roles.replace","grants:write",()->{
            Set<Long> ids=ManagementProcesses.grantIds(body);
            for(long role:ids) if(!"BUSINESS".equals(db.one("SELECT purpose FROM role WHERE id=#{p.id}","id",role).get("purpose"))) throw Problem.bad("员工只能分配业务角色");
            if(db.execute("UPDATE employee SET grants_version=grants_version+1 WHERE id=#{p.id} AND grants_version=#{p.version}","id",id,"version",ManagementProcesses.number(body,"version"))!=1) throw Problem.conflict();
            db.execute("DELETE FROM employee_role WHERE employee_id=#{p.id}","id",id);
            for(long role:ids) db.execute("INSERT INTO employee_role(employee_id,role_id) VALUES(#{p.id},#{p.role})","id",id,"role",role);
            return null;
        });
    }
    public void update(long actor,long id,Map<String,Object> body) {
        management.change(actor,"employee.status.update","grants:write",()->{
            if(db.execute("UPDATE employee SET enabled=#{p.enabled},version=version+1 WHERE id=#{p.id} AND version=#{p.version}","id",id,"version",ManagementProcesses.number(body,"version"),"enabled",ManagementProcesses.flag(body,"enabled"))!=1) throw Problem.conflict();
            return null;
        });
    }
    public Map<String,Object> effective(long id) {
        db.one("SELECT id FROM employee WHERE id=#{p.id}","id",id);
        Set<String> codes=new TreeSet<>();
        for(var app:db.list("SELECT id FROM resource WHERE kind='APPLICATION' AND code<>'management'")) codes.addAll(authorization.effective(id,((Number)app.get("id")).longValue()));
        return Map.of("permissions",codes);
    }
}
