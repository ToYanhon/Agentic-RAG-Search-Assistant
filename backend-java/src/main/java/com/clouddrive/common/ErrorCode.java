package com.clouddrive.common;

/**
 * 稳定错误码，对应 Go internal/response 常量。客户端不得依赖错误文本判断分支。
 */
public final class ErrorCode {

	public static final int BAD_REQUEST = 40000;

	public static final int UNAUTHORIZED = 40100;

	public static final int FORBIDDEN = 40300;

	public static final int NOT_FOUND = 40400;

	public static final int CONFLICT = 40900;

	public static final int INTERNAL = 50000;

	public static final int UNPROCESSABLE = 42200;

	public static final int INVALID_CREDENTIALS = 40101;

	public static final int TOKEN_EXPIRED = 40102;

	public static final int USERNAME_TAKEN = 40901;

	public static final int EMAIL_TAKEN = 40902;

	public static final int FOLDER_CYCLE = 42201;

	public static final int FILE_TOO_LARGE = 41300;

	public static final int STORAGE_EXCEEDED = 41301;

	private ErrorCode() {
	}

}
