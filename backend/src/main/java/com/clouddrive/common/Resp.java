package com.clouddrive.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;

/**
 * 统一响应信封，对齐 Go pkg/response.Resp：
 * {@code {"code": int, "message": string, "data": ...}}，data 为空时不输出。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Resp<T> {

    private int code;
    private String message;
    private T data;

    public Resp() {
    }

    public Resp(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Resp<T> ok(T data) {
        return new Resp<>(0, "success", data);
    }

    public static <T> Resp<T> created(T data) {
        return new Resp<>(0, "created", data);
    }

    public static <T> Resp<T> error(int code, String message) {
        return new Resp<>(code, message, null);
    }
}
