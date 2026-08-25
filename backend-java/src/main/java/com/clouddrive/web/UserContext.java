package com.clouddrive.web;

/**
 * 请求级认证上下文：由 AuthFilter 写入，controller/service 读取。 对应 Go httpapi 的 context
 * keys（user_id、caller）。
 */
public final class UserContext {

	private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

	private static final ThreadLocal<Caller> CALLER = new ThreadLocal<>();

	private UserContext() {
	}

	public static void set(long userId, Caller caller) {
		USER_ID.set(userId);
		CALLER.set(caller);
	}

	public static long userId() {
		Long value = USER_ID.get();
		return value == null ? 0L : value;
	}

	public static Caller caller() {
		return CALLER.get();
	}

	public static boolean isAgent() {
		return CALLER.get() == Caller.AGENT;
	}

	public static void clear() {
		USER_ID.remove();
		CALLER.remove();
	}

}
