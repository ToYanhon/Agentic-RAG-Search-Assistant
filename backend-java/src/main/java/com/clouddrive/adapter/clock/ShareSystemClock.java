package com.clouddrive.adapter.clock;

import com.clouddrive.share.Clock;

import java.time.LocalDateTime;

/**
 * 分享服务系统时钟，对应 Go security.SystemClock。
 */
@org.springframework.stereotype.Component
public class ShareSystemClock implements Clock {

	@Override
	public LocalDateTime now() {
		return LocalDateTime.now();
	}

}