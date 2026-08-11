package com.clouddrive.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常 → 统一信封 {code,message,data}，对齐 Go response.AppError。
 * 校验失败（对齐 gin binding）：HTTP 400 + code 40000。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AppException.class)
    public ResponseEntity<Resp<Void>> handleApp(AppException e) {
        return build(e.getCode(), e.userMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Resp<Void>> handleValidation(MethodArgumentNotValidException e) {
        FieldError fe = e.getBindingResult().getFieldError();
        String msg = fe == null ? "bad request" : fe.getDefaultMessage();
        return build(ErrorCode.BAD_REQUEST, msg);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Resp<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        return build(ErrorCode.BAD_REQUEST, "bad request body");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Resp<Void>> handleNoResource(NoResourceFoundException e) {
        return build(ErrorCode.NOT_FOUND, "not found");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Resp<Void>> handleMaxUpload(MaxUploadSizeExceededException e) {
        return build(ErrorCode.FILE_TOO_LARGE, "file too large");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Resp<Void>> handleUnknown(Exception e) {
        log.error("unhandled exception", e);
        return build(ErrorCode.INTERNAL, "internal error");
    }

    private ResponseEntity<Resp<Void>> build(ErrorCode code, String message) {
        return ResponseEntity.status(code.getHttpStatus())
                .body(Resp.error(code.getCode(), message));
    }
}
