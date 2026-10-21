// One-time local bootstrap after Flyway. Reads only password hashes from the ignored local file.

package edu.municipal.access;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class Bootstrap implements ApplicationRunner {
    private final Store db;
    private final TransactionTemplate transaction;
    private final String file;
    public Bootstrap(Store db, PlatformTransactionManager manager, @Value("${app.bootstrap-file}") String file) {
        this.db = db; this.transaction = new TransactionTemplate(manager); this.file = file;
    }
    @Override public void run(ApplicationArguments arguments) throws Exception {
        Path path = Path.of(file);
        if (!Files.isRegularFile(path)) return;
        var settings = new ObjectMapper().readTree(path.toFile());
        String username = settings.path("username").asText();
        String adminHash = settings.path("passwordHash").asText(), demoHash = settings.path("demoPasswordHash").asText();
        if (!username.matches("[a-z][a-z0-9._-]{2,63}") || !adminHash.startsWith("$2") || !demoHash.startsWith("$2")) throw new IllegalStateException("Invalid local bootstrap configuration");
        transaction.executeWithoutResult(status -> {
            db.one("SELECT id FROM policy_lock WHERE id=1 FOR UPDATE");
            // A nonempty account table always disables bootstrap; this is never an HTTP admin bypass.
            if (db.number("SELECT COUNT(*) FROM account") != 0) return;
            long admin = db.insert("INSERT INTO account(username,display_name) VALUES(#{p.name},'开发管理员')", Store.parameters("name", username));
            db.execute("INSERT INTO credential(account_id,password_hash) VALUES(#{p.id},#{p.hash})", "id", admin, "hash", adminHash);
            db.execute("INSERT INTO user_role(account_id,role_id) VALUES(#{p.id},1)", "id", admin);
            long demo = db.insert("INSERT INTO account(username,display_name,employee_id) VALUES('demo_user','演示员工账号',1)", Store.parameters());
            db.execute("INSERT INTO credential(account_id,password_hash) VALUES(#{p.id},#{p.hash})", "id", demo, "hash", demoHash);
            db.execute("INSERT INTO user_role(account_id,role_id) VALUES(#{p.id},2)", "id", demo);
        });
    }
}
