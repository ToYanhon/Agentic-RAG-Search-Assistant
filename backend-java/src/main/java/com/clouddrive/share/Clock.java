package com.clouddrive.share;

import java.time.LocalDateTime;

/**
 * 时钟端口，对应 Go share.Clock。
 */
public interface Clock {

	LocalDateTime now();

}