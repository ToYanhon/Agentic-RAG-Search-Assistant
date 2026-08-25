package com.clouddrive.share;

import com.clouddrive.auth.RandomHex;
import com.clouddrive.common.Errors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShareServiceTest {

	private final Repository shares = mock(Repository.class);

	private final FileAccess files = mock(FileAccess.class);

	private final Clock clock = mock(Clock.class);

	private final RandomHex tokens = mock(RandomHex.class);

	private final Cache cache = mock(Cache.class);

	private final ShareService service = new ShareService(shares, files, clock, tokens, cache);

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 16, 12, 0, 0);

	private Record shareRecord() {
		return new Record(1, 42, 7, "token-abc", null, NOW);
	}

	private static com.clouddrive.file.Record fileRecord(long id, long owner) {
		return new com.clouddrive.file.Record(id, owner, null, "a.txt", 10, "text/plain", "m", "k", NOW);
	}

	@BeforeEach
	void setUp() {
		when(clock.now()).thenReturn(NOW);
	}

	@Test
	void createRejectsForeignFile() {
		when(files.find(42)).thenReturn(fileRecord(42, 8));
		assertThrows(Errors.AccessDenied.class, () -> service.create(7, 42, null));
	}

	@Test
	void createWithExpiry() {
		when(files.find(42)).thenReturn(fileRecord(42, 7));
		when(tokens.generate(32)).thenReturn("token-abc");
		Record withExpiry = new Record(1, 42, 7, "token-abc", NOW.plusHours(24), NOW);
		when(shares.create(7, 42, "token-abc", NOW.plusHours(24))).thenReturn(withExpiry);
		Record record = service.create(7, 42, 24);
		assertEquals("token-abc", record.token());
		assertEquals(NOW.plusHours(24), record.expiresAt());
	}

	@Test
	void accessFromCache() {
		when(cache.get("share:token-abc")).thenReturn(shareRecord());
		when(files.find(42)).thenReturn(fileRecord(42, 7));
		com.clouddrive.file.Record file = service.access("token-abc");
		assertEquals(42, file.id());
		verify(shares, never()).findByToken(anyString());
	}

	@Test
	void accessExpiredDenied() {
		Record expired = new Record(1, 42, 7, "token-abc", NOW.minusHours(1), NOW);
		when(cache.get("share:token-abc")).thenReturn(expired);
		assertThrows(Errors.ShareNotFound.class, () -> service.access("token-abc"));
	}

	@Test
	void accessLoadsFromDBOnCacheMiss() {
		when(cache.get("share:token-abc")).thenReturn(null);
		when(shares.findByToken("token-abc")).thenReturn(shareRecord());
		when(files.find(42)).thenReturn(fileRecord(42, 7));
		com.clouddrive.file.Record file = service.access("token-abc");
		assertEquals(42, file.id());
		verify(cache).set(eq("share:token-abc"), any(Record.class), any());
	}

	@Test
	void accessUnknownTokenDenied() {
		when(cache.get("share:unknown")).thenReturn(null);
		when(shares.findByToken("unknown")).thenThrow(new Errors.ShareNotFound("share not found"));
		assertThrows(Errors.ShareNotFound.class, () -> service.access("unknown"));
	}

	@Test
	void revokeDeletesCache() {
		when(shares.findOwned(1, 7)).thenReturn(shareRecord());
		service.revoke(7, 1);
		verify(shares).delete(1);
		verify(cache).delete("share:token-abc");
	}

	@Test
	void downloadReturnsBody() {
		when(cache.get("share:token-abc")).thenReturn(shareRecord());
		com.clouddrive.file.Record file = fileRecord(42, 7);
		when(files.find(42)).thenReturn(file);
		when(files.get("k")).thenReturn(new ByteArrayInputStream(new byte[] { 1 }));
		com.clouddrive.file.Download download = service.download("token-abc");
		assertTrue(download.body() != null);
		assertEquals(42, download.record().id());
	}

}