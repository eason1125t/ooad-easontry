// Uniform safe business errors; remote payloads, SQL and application keys never enter responses.
package edu.municipal.documents;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class OfficeErrors {
    @ExceptionHandler(OfficeProblem.class) ResponseEntity<?> known(OfficeProblem error) {return ResponseEntity.status(error.status).body(Map.of("code",error.code,"message",error.getMessage()));}
    @ExceptionHandler({HttpMessageNotReadableException.class,IllegalArgumentException.class}) ResponseEntity<?> input() {return ResponseEntity.badRequest().body(Map.of("code","INVALID_INPUT","message","请求格式不正确"));}
    @ExceptionHandler(Exception.class) ResponseEntity<?> other() {return ResponseEntity.internalServerError().body(Map.of("code","INTERNAL_ERROR","message","业务处理失败，未确认成功，请重新读取"));}
}
