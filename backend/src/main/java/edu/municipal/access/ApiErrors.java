// Central HTTP failure mapping; database SQL, credentials and stack traces never enter responses.

package edu.municipal.access;
import java.util.Map;
import java.sql.SQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(Problem.class)
    ResponseEntity<?> problem(Problem failure) {
        return ResponseEntity.status(failure.status).body(Map.of("code", failure.code, "message", failure.getMessage()));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> integrity(DataIntegrityViolationException failure) {
        // Classify the known unique constraint without returning SQL or bound values.
        if (failure.getMostSpecificCause() instanceof SQLException sql && sql.getErrorCode() == 1062
                && sql.getMessage() != null && sql.getMessage().contains("employee_id")) {
            return ResponseEntity.status(409).body(Map.of("code", "EMPLOYEE_ALREADY_LINKED", "message", "该员工已关联其他账号，请重新查找未关联员工，或保留账号不关联员工资料"));
        }
        return ResponseEntity.status(409).body(Map.of("code", "DATA_CONFLICT", "message", "编码、员工关联或授权关系重复，或引用对象无效"));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> input() {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_INPUT", "message", "请求格式或字段类型不正确"));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected() {
        return ResponseEntity.status(500).body(Map.of("code", "INTERNAL_ERROR", "message", "服务内部处理失败，请稍后重试"));
    }
}
