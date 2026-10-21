// Spring entry point. Functional processes, persistence and HTTP adapters live in this package.

package edu.municipal.access;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class AccessApplication {
    public static void main(String[] args) { SpringApplication.run(AccessApplication.class, args); }
    @Bean Clock clock() { return Clock.systemUTC(); }
}
