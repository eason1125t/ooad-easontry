// Independent document-system entry point; RBAC data remains behind its remote HTTP contract.
package edu.municipal.documents;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DocumentApplication {
    public static void main(String[] args) { SpringApplication.run(DocumentApplication.class,args); }
}
