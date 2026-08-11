package com.clouddrive.common;

import lombok.Getter;

/**
 * 错误码与 HTTP 状态映射，对齐 Go pkg/apperror 的 Code/HTTP 表。
 */
@Getter
public enum ErrorCode {

    BAD_REQUEST(40000, 400),
    UNAUTHORIZED(40100, 401),
    FORBIDDEN(40300, 403),
    NOT_FOUND(40400, 404),
    CONFLICT(40900, 409),
    INTERNAL(50000, 500),
    UNPROCESSABLE(42200, 422),
    INVALID_CREDS(40101, 401),
    TOKEN_EXPIRED(40102, 401),
    USERNAME_TAKEN(40901, 409),
    EMAIL_TAKEN(40902, 409),
    FILE_TOO_LARGE(41300, 413),
    STORAGE_EXCEEDED(41301, 413),
    FOLDER_CYCLE(42201, 422);

    private final int code;
    private final int httpStatus;

    ErrorCode(int code, int httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }
}
