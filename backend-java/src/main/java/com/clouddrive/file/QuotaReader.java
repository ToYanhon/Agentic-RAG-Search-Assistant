package com.clouddrive.file;

/**
 * 配额读取端口，对应 Go file.QuotaReader。
 */
public interface QuotaReader {

	/** 返回 (remaining, ok)；用户不存在时 ok=false。 */
	Remaining remaining(long ownerId);

	record Remaining(long bytes, boolean ok) {
	}

}