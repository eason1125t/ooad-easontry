// Central HTTP failure mapping; database SQL, credentials and stack traces never enter responses.

package edu.municipal.access;
import java.util.Map;
import java.util.LinkedHashMap;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(Problem.class)
    ResponseEntity<?> problem(Problem failure,HttpServletRequest request) {
        return error(failure.status,failure.code,failure.getMessage(),request);
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
    ResponseEntity<?> input(HttpServletRequest request) {
        return error(400,request.getAttribute("authorizationRequestId")==null ? "INVALID_INPUT" : "INVALID_CHECK_REQUEST","请求格式或字段类型不正确",request);
    }
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<?> missing(HttpServletRequest request) { return error(404,"NOT_FOUND","接口或资源不存在",request); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(HttpServletRequest request) {
        return error(500,"INTERNAL_ERROR","服务内部处理失败，请稍后重试",request);
    }
    private ResponseEntity<?> error(int status,String code,String message,HttpServletRequest request) {
        Map<String,Object> body=new LinkedHashMap<>(Map.of("code",code,"message",message));
        if(request.getAttribute("authorizationRequestId")!=null) body.put("requestId",request.getAttribute("authorizationRequestId"));
        return ResponseEntity.status(status).body(body);
    }
}
