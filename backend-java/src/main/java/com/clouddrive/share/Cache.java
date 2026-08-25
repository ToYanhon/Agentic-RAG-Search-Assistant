package com.clouddrive.share;

import java.time.Duration;

/**
 * 分享缓存端口，对应 Go share.Cache。token 未命中时返回 null。
 */
public interface Cache {

	Record get(String token);

	void set(String token, Record record, Duration ttl);

	void delete(String token);

}