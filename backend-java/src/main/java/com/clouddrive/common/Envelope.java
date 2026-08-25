package com.clouddrive.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一响应信封，对应 Go internal/response。成功 code=0；data 为 null 时省略（对应 omitempty）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Envelope<T>(int code, String message, T data) {

	public static <T> Envelope<T> ok(T data) {
		return new Envelope<>(0, "success", data);
	}

	public static <T> Envelope<T> created(T data) {
		return new Envelope<>(0, "created", data);
	}

	public static Envelope<Void> error(int status, int code, String message) {
		return new Envelope<>(code, message, null);
	}
}
