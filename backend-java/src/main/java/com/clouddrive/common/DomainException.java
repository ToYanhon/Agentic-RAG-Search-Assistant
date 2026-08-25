package com.clouddrive.common;

/**
 * 领域异常基类，对应 Go 各包导出的 error 变量（如 auth.ErrNotFound）。 由 controller/httpapi 层按端点映射为稳定 HTTP
 * 状态码与业务错误码。
 */
public abstract class DomainException extends RuntimeException {

	protected DomainException(String message) {
		super(message);
	}

}