package com.clouddrive.multipart;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * multipart 清理 worker，对应 Go multipart.CleanupWorker。每 30 分钟终止 Redis 会话已过期的 users/ 上传。
 */
@org.springframework.stereotype.Component
public class CleanupWorker {

	private static final Logger log = LoggerFactory.getLogger(CleanupWorker.class);

	private final ObjectStore storage;

	private final Metadata metadata;

	public CleanupWorker(ObjectStore storage, Metadata metadata) {
		this.storage = storage;
		this.metadata = metadata;
	}

	@Scheduled(fixedDelay = 30 * 60 * 1000L, initialDelay = 30 * 60 * 1000L)
	public void cleanup() {
		int aborted = 0;
		try {
			for (IncompleteUpload upload : storage.incompleteUploads()) {
				if (!upload.objectKey().startsWith("users/")) {
					continue;
				}
				boolean exists;
				try {
					exists = metadata.exists(upload.uploadId());
				}
				catch (RuntimeException e) {
					log.warn("multipart cleanup metadata check failed upload_id={}: {}", upload.uploadId(),
							e.getMessage());
					continue;
				}
				if (exists) {
					continue;
				}
				try {
					storage.abortMultipart(upload.objectKey(), upload.uploadId());
					aborted++;
				}
				catch (RuntimeException e) {
					log.warn("multipart cleanup abort failed upload_id={}: {}", upload.uploadId(), e.getMessage());
				}
			}
		}
		catch (RuntimeException e) {
			log.warn("multipart cleanup list failed: {}", e.getMessage());
			return;
		}
		if (aborted > 0) {
			log.info("multipart cleanup aborted {} expired uploads", aborted);
		}
	}

}