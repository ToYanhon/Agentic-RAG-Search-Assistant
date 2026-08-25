package com.clouddrive.indexnotify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexNotifyServiceTest {

	private FakeQueue queue;

	private FakeSender sender;

	private MutableClock clock;

	private IndexNotifyService service;

	private static class FakeQueue implements Queue {

		final Deque<String> items = new ArrayDeque<>();

		@Override
		public void push(String json) {
			items.addLast(json);
		}

		@Override
		public String pop() {
			return items.isEmpty() ? null : items.removeFirst();
		}

	}

	private static class FakeSender implements Sender {

		final List<String[]> calls = new ArrayList<>();

		boolean fail;

		@Override
		public boolean send(String kind, long fileId, long ownerId) {
			calls.add(new String[] { kind, Long.toString(fileId), Long.toString(ownerId) });
			return !fail;
		}

	}

	private static class MutableClock implements Clock {

		long epoch = 1_000_000L;

		@Override
		public Instant now() {
			return Instant.ofEpochSecond(epoch);
		}

	}

	@BeforeEach
	void setUp() {
		queue = new FakeQueue();
		sender = new FakeSender();
		clock = new MutableClock();
		service = new IndexNotifyService(queue, sender, clock, new ObjectMapper());
	}

	@Test
	void reindexPushesTaskToQueue() throws Exception {
		service.reindex(11, 22);
		assertEquals(1, queue.items.size());
		Task task = new ObjectMapper().readValue(queue.items.peek(), Task.class);
		assertEquals("reindex", task.type());
		assertEquals(11, task.fileId());
		assertEquals(22, task.ownerId());
	}

	@Test
	void drainOnceSendsTask() {
		service.reindex(11, 22);
		service.drainOnce();
		assertEquals(1, sender.calls.size());
		assertEquals("reindex", sender.calls.get(0)[0]);
		assertTrue(queue.items.isEmpty());
	}

	@Test
	void drainOnceRetriesThenDrops() {
		sender.fail = true;
		service.reindex(11, 22);
		service.drainOnce();
		// 第 1 次失败，attempts=1, nextRetry = now + 2
		assertEquals(1, queue.items.size());
		clock.epoch += 3;
		service.drainOnce();
		// 第 2 次失败，attempts=2
		assertEquals(1, queue.items.size());
		clock.epoch += 5;
		service.drainOnce();
		// 第 3 次失败，attempts+1 >= 3 -> 丢弃
		assertTrue(queue.items.isEmpty());
		assertEquals(3, sender.calls.size());
	}

	@Test
	void drainOnceRespectsNextRetry() {
		sender.fail = true;
		service.reindex(11, 22);
		service.drainOnce();
		// 尚未到 next_retry，重排回队列
		service.drainOnce();
		assertEquals(1, queue.items.size());
		assertEquals(1, sender.calls.size());
	}

	@Test
	void unindexUsesUnindexType() {
		service.unindex(33, 44);
		service.drainOnce();
		assertEquals("unindex", sender.calls.get(0)[0]);
	}

}