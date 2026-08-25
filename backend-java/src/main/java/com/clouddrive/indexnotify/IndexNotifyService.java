package com.clouddrive.indexnotify;

import com.clouddrive.file.Notifier;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 索引通知编排，对应 Go indexnotify.Service。实现 file.Notifier；每 2 秒排空一个任务。
 */
@org.springframework.stereotype.Service
public class IndexNotifyService implements Notifier {

	private static final Logger log = LoggerFactory.getLogger(IndexNotifyService.class);

	private static final int MAX_ATTEMPTS = 3;

	private final Queue queue;

	private final Sender sender;

	private final Clock clock;

	private final ObjectMapper mapper;

	public IndexNotifyService(Queue queue, Sender sender, Clock clock, ObjectMapper mapper) {
		this.queue = queue;
		this.sender = sender;
		this.clock = clock;
		this.mapper = mapper;
	}

	@Override
	public void reindex(long fileId, long ownerId) {
		notify("reindex", fileId, ownerId);
	}

	@Override
	public void unindex(long fileId, long ownerId) {
		notify("unindex", fileId, ownerId);
	}

	private void notify(String kind, long fileId, long ownerId) {
		Task task = new Task(kind, fileId, ownerId, 0, 0);
		try {
			String raw = mapper.writeValueAsString(task);
			queue.push(raw);
			return;
		}
		catch (JsonProcessingException | RuntimeException ignored) {
			// 序列化或入队失败，回退直发
		}
		CompletableFutureSupport.runAsync(() -> send(kind, fileId, ownerId));
	}

	@Scheduled(fixedDelay = 2000L)
	public void drainOnce() {
		String raw;
		try {
			raw = queue.pop();
		}
		catch (RuntimeException e) {
			return;
		}
		if (raw == null) {
			return;
		}
		Task task;
		try {
			task = mapper.readValue(raw, Task.class);
		}
		catch (JsonProcessingException e) {
			return;
		}
		if (task.fileId() <= 0 || task.ownerId() <= 0
				|| (!"reindex".equals(task.type()) && !"unindex".equals(task.type()))) {
			return;
		}
		if (task.nextRetry() > clock.now().getEpochSecond()) {
			queue.push(raw);
			return;
		}
		if (send(task.type(), task.fileId(), task.ownerId())) {
			return;
		}
		if (task.attempts() + 1 >= MAX_ATTEMPTS) {
			log.warn("index notify dropped after {} attempts for file {} type={}", task.attempts() + 1, task.fileId(),
					task.type());
			return;
		}
		int attempts = task.attempts() + 1;
		long nextRetry = clock.now().getEpochSecond() + Math.min(2L * (1L << (attempts - 1)), 30L);
		Task next = new Task(task.type(), task.fileId(), task.ownerId(), attempts, nextRetry);
		try {
			queue.push(mapper.writeValueAsString(next));
		}
		catch (JsonProcessingException ignored) {
			// 重排失败则丢弃
		}
	}

	private boolean send(String kind, long fileId, long ownerId) {
		try {
			return sender.send(kind, fileId, ownerId);
		}
		catch (RuntimeException e) {
			return false;
		}
	}

}

/**
 * 异步执行辅助，对应 Go 的 go 语句。
 */
final class CompletableFutureSupport {

	private static final java.util.concurrent.ExecutorService EXECUTOR = java.util.concurrent.Executors
		.newCachedThreadPool(r -> {
			Thread thread = new Thread(r, "index-notify-fallback");
			thread.setDaemon(true);
			return thread;
		});

	private CompletableFutureSupport() {
	}

	static void runAsync(Runnable runnable) {
		EXECUTOR.execute(runnable);
	}

}