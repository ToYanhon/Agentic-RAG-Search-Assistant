package com.clouddrive.file;

import java.time.Duration;

/**
 * 秒传校验缓存端口，对应 Go file.ChecksumCache。
 */
public interface ChecksumCache {

	/** 返回 (exists, hit)；缓存未命中时 hit=false。 */
	ChecksumResult get(long ownerId, String md5);

	void set(long ownerId, String md5, boolean exists, Duration ttl);

	void delete(long ownerId, String md5);

	record ChecksumResult(boolean exists, boolean hit) {
	}

}