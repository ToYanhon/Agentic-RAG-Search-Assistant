package com.clouddrive.file;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 对象删除 worker，对应 Go file.DeletionWorker。每 2 秒清理一批（最多 20），失败指数退避。
 */
@org.springframework.stereotype.Component
public class DeletionWorker {

	private static final Logger log = LoggerFactory.getLogger(DeletionWorker.class);

	private final ObjectDeletionQueue queue;

	private final ObjectStore objects;

	public DeletionWorker(ObjectDeletionQueue queue, ObjectStore objects) {
		this.queue = queue;
		this.objects = objects;
	}

	@org.springframework.beans.factory.annotation.Value("${deletion.worker.enabled:true}")
	private boolean enabled;

	@Scheduled(fixedDelay = 2000L)
	public void drain() {
		if (!enabled) {
			return;
		}
		try {
			for (DeletionTask task : queue.pending(20)) {
				try {
					objects.delete(task.objectKey());
					queue.complete(task.id());
				}
				catch (RuntimeException e) {
					log.warn("object deletion retry for task {}: {}", task.id(), e.getMessage());
					try {
						queue.retry(task.id(), 1);
					}
					catch (RuntimeException retryErr) {
						log.warn("object deletion retry update failed for task {}: {}", task.id(),
								retryErr.getMessage());
					}
				}
			}
		}
		catch (RuntimeException e) {
			log.warn("object deletion outbox read failed: {}", e.getMessage());
		}
	}

}