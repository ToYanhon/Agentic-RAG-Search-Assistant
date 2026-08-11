package com.clouddrive.common;

import lombok.Getter;

/**
 * 业务异常，对齐 Go pkg/apperror.AppError（code + message）。
 */
@Getter
public class AppException extends RuntimeException {

    private final ErrorCode code;

    public AppException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    /** 对外可见消息：>=50000 的内部错误只给统一文案（对齐 UserMessage），其余返回业务消息。 */
    public String userMessage() {
        if (code == ErrorCode.INTERNAL) {
            return "internal error";
        }
        return getMessage();
    }

    public static AppException badRequest(String msg) {
        return new AppException(ErrorCode.BAD_REQUEST, msg);
    }

    public static AppException unauthorized(String msg) {
        return new AppException(ErrorCode.UNAUTHORIZED, msg);
    }

    public static AppException forbidden(String msg) {
        return new AppException(ErrorCode.FORBIDDEN, msg);
    }

    public static AppException notFound(String msg) {
        return new AppException(ErrorCode.NOT_FOUND, msg);
    }

    public static AppException conflict(String msg) {
        return new AppException(ErrorCode.CONFLICT, msg);
    }

    public static AppException unprocessable(String msg) {
        return new AppException(ErrorCode.UNPROCESSABLE, msg);
    }

    public static AppException invalidCreds(String msg) {
        return new AppException(ErrorCode.INVALID_CREDS, msg);
    }

    public static AppException usernameTaken(String msg) {
        return new AppException(ErrorCode.USERNAME_TAKEN, msg);
    }

    public static AppException emailTaken(String msg) {
        return new AppException(ErrorCode.EMAIL_TAKEN, msg);
    }

    public static AppException fileTooLarge(String msg) {
        return new AppException(ErrorCode.FILE_TOO_LARGE, msg);
    }

    public static AppException storageExceeded(String msg) {
        return new AppException(ErrorCode.STORAGE_EXCEEDED, msg);
    }

    public static AppException folderCycle(String msg) {
        return new AppException(ErrorCode.FOLDER_CYCLE, msg);
    }

    public static AppException internal(String msg) {
        return new AppException(ErrorCode.INTERNAL, msg);
    }
}
