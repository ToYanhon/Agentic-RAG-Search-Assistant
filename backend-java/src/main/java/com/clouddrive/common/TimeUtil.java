package com.clouddrive.common;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 时间格式化工具。与 Go 后端一致：created_at 输出 UTC "2006-01-02T15:04:05Z"； 存储值按 Asia/Shanghai 解读后转为
 * UTC（与 Go DSN loc=Asia/Shanghai + .UTC() 对齐）。
 */
public final class TimeUtil {

	private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

	private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

	private TimeUtil() {
	}

	public static String format(LocalDateTime value) {
		if (value == null) {
			return "";
		}
		return value.atZone(SHANGHAI).withZoneSameInstant(ZoneOffset.UTC).format(FORMATTER);
	}

	/**
	 * 按原始存储墙钟格式化（Go profileOf 直接 Format 本地时间，不做时区转换）。
	 */
	public static String formatLocal(LocalDateTime value) {
		if (value == null) {
			return "";
		}
		return FORMATTER.format(value);
	}

}