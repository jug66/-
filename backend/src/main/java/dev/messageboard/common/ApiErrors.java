package dev.messageboard.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<?> uploadSize(Exception ex, HttpServletRequest req) { return response(413, "图片大小超过限制（单张 5MB，单批 16MB）", req); }
    @ExceptionHandler({org.springframework.web.multipart.MultipartException.class, org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    ResponseEntity<?> multipart(Exception ex, HttpServletRequest req) { return response(400, "图片上传请求不正确", req); }
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> api(ApiException ex, HttpServletRequest req) { return response(ex.status, ex.getMessage(), req); }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    ResponseEntity<?> bad(Exception ex, HttpServletRequest req) { return response(400, "请求参数不正确或包含不支持的字段", req); }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> database(Exception ex, HttpServletRequest req) {
        return response(503, "数据库暂时繁忙，请保留草稿稍后重试", req);
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception ex, HttpServletRequest req) {
        // 只记异常类型和请求号，避免 SQL 参数、密码、正文随堆栈泄露。
        LoggerFactory.getLogger(ApiErrors.class).error("request={} failure={}", req.getAttribute("requestId"), ex.getClass().getSimpleName());
        return response(500, "服务暂时不可用，请稍后重试", req);
    }
    static ResponseEntity<?> response(int status, String message, HttpServletRequest req) {
        return ResponseEntity.status(status).body(Map.of("message", message, "requestId", String.valueOf(req.getAttribute("requestId"))));
    }
}
