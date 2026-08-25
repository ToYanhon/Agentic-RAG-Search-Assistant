package com.clouddrive.indexnotify;

import java.time.Instant;

/**
 * 时钟端口，对应 Go indexnotify.Clock。
 */
public interface Clock {

	Instant now();

}