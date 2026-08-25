package com.clouddrive.common;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态码与稳定业务错误码，由 GlobalExceptionHandler 渲染为统一信封。
 */
public class ApiException extends RuntimeException {

	private final int status;

	private final int code;

	public ApiException(int status, int code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public static ApiException badRequest(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST.value(), ErrorCode.BAD_REQUEST, message);
	}

	public static ApiException unauthorized(String message) {
		return new ApiException(HttpStatus.UNAUTHORIZED.value(), ErrorCode.UNAUTHORIZED, message);
	}

	public static ApiException forbidden(String message) {
		return new ApiException(HttpStatus.FORBIDDEN.value(), ErrorCode.FORBIDDEN, message);
	}

	public static ApiException notFound(String message) {
		return new ApiException(HttpStatus.NOT_FOUND.value(), ErrorCode.NOT_FOUND, message);
	}

	public static ApiException conflict(String message) {
		return new ApiException(HttpStatus.CONFLICT.value(), ErrorCode.CONFLICT, message);
	}

	public static ApiException unprocessable(String message) {
		return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY.value(), ErrorCode.UNPROCESSABLE, message);
	}

	public static ApiException internal(String message) {
		return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR.value(), ErrorCode.INTERNAL, message);
	}

	public int getStatus() {
		return status;
	}

	public int getCode() {
		return code;
	}

}
