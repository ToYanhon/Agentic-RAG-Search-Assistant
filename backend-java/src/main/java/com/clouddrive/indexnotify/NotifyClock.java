package com.clouddrive.indexnotify;

import java.time.Instant;

/**
 * 索引通知系统时钟。
 */
@org.springframework.stereotype.Component
public class NotifyClock implements Clock {

	@Override
	public Instant now() {
		return Instant.now();
	}

}