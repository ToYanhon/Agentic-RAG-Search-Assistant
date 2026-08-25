package com.clouddrive.file;

import com.clouddrive.auth.ProfileCache;
import com.clouddrive.common.Errors;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileServiceTest {

	private final Repository records = mock(Repository.class);

	private final FolderTree folders = mock(FolderTree.class);

	private final QuotaReader quota = mock(QuotaReader.class);

	private final ObjectStore objects = mock(ObjectStore.class);

	private final KeyGenerator keys = mock(KeyGenerator.class);

	private final Notifier notifier = mock(Notifier.class);

	private final ProfileCache profiles = mock(ProfileCache.class);

	private final ChecksumCache checksums = mock(ChecksumCache.class);

	private final FileService service = new FileService(records, folders, quota, objects, keys, notifier, profiles,
			checksums);

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 16, 12, 0, 0);

	private Record record(long id, long owner) {
		return new Record(id, owner, null, "notes.txt", 10, "text/plain", "abc", "users/7/k", NOW);
	}

	@Test
	void uploadQuotaExceeded() {
		when(quota.remaining(7)).thenReturn(new QuotaReader.Remaining(5, true));
		assertThrows(Errors.StorageExceeded.class, () -> service.upload(7, null, "a.txt", "text/plain", new byte[10]));
	}

	@Test
	void uploadRejectsForeignFolder() {
		when(quota.remaining(7)).thenReturn(new QuotaReader.Remaining(1000, true));
		when(folders.findFolderOwner(2)).thenReturn(8L);
		assertThrows(Errors.AccessDenied.class, () -> service.upload(7, 2L, "a.txt", "text/plain", new byte[10]));
	}

	@Test
	void uploadSuccessNotifiesReindex() {
		when(quota.remaining(7)).thenReturn(new QuotaReader.Remaining(1000, true));
		when(keys.newKey(7)).thenReturn("users/7/key1");
		when(records.createWithQuota(any()))
			.thenReturn(new Record(5, 7, null, "a.txt", 3, "text/plain", "md5", "users/7/key1", NOW));
		Record record = service.upload(7, null, "a.txt", "text/plain", new byte[] { 1, 2, 3 });
		assertEquals(5, record.id());
		verify(objects).put(eq("users/7/key1"), eq("text/plain"), any(), eq(3L));
		verify(notifier).reindex(5, 7);
		verify(checksums).delete(eq(7L), any(String.class));
	}

	@Test
	void uploadDeletesObjectOnDbFailure() {
		when(quota.remaining(7)).thenReturn(new QuotaReader.Remaining(1000, true));
		when(keys.newKey(7)).thenReturn("users/7/key1");
		when(records.createWithQuota(any())).thenThrow(new RuntimeException("db error"));
		assertThrows(RuntimeException.class, () -> service.upload(7, null, "a.txt", "text/plain", new byte[] { 1 }));
		verify(objects).delete("users/7/key1");
	}

	@Test
	void checksumInstantCachedNegative() {
		when(checksums.get(7, "md5")).thenReturn(new ChecksumCache.ChecksumResult(false, true));
		FileService.InstantResult result = service.checksumInstant(7, "md5", "a.txt", 10, null);
		assertFalse(result.instant());
		verify(records, never()).findByMd5Owner(anyLong(), any(String.class));
	}

	@Test
	void checksumInstantNotFoundCachesNegative() {
		when(checksums.get(7, "md5")).thenReturn(new ChecksumCache.ChecksumResult(false, false));
		when(records.findByMd5Owner(7, "md5")).thenThrow(new Errors.NotFound("file not found"));
		FileService.InstantResult result = service.checksumInstant(7, "md5", "a.txt", 10, null);
		assertFalse(result.instant());
		verify(checksums).set(eq(7L), eq("md5"), eq(false), eq(Duration.ofMinutes(1)));
	}

	@Test
	void checksumInstantSizeMismatch() {
		when(checksums.get(7, "md5")).thenReturn(new ChecksumCache.ChecksumResult(false, false));
		when(records.findByMd5Owner(7, "md5")).thenReturn(record(3, 7));
		FileService.InstantResult result = service.checksumInstant(7, "md5", "a.txt", 99, null);
		assertFalse(result.instant());
		verify(objects, never()).copy(any(), any());
	}

	@Test
	void checksumInstantHitCopiesToNewKey() {
		when(checksums.get(7, "md5")).thenReturn(new ChecksumCache.ChecksumResult(false, false));
		when(records.findByMd5Owner(7, "md5")).thenReturn(record(3, 7));
		when(quota.remaining(7)).thenReturn(new QuotaReader.Remaining(1000, true));
		when(keys.newKey(7)).thenReturn("users/7/newkey");
		when(records.createWithQuota(any()))
			.thenReturn(new Record(9, 7, null, "a.txt", 10, "text/plain", "md5", "users/7/newkey", NOW));
		FileService.InstantResult result = service.checksumInstant(7, "md5", "a.txt", 10, null);
		assertTrue(result.instant());
		verify(objects).copy("users/7/k", "users/7/newkey");
		verify(notifier).reindex(9, 7);
	}

	@Test
	void overwriteRejectsNonTextFile() {
		when(records.find(1)).thenReturn(new Record(1, 7, null, "photo.png", 10, "image/png", "abc", "users/7/k", NOW));
		assertThrows(Errors.NotTextFile.class, () -> service.overwriteContent(7, 1, "hello"));
	}

	@Test
	void deleteNotifiesUnindex() {
		when(records.find(1)).thenReturn(record(1, 7));
		service.delete(7, 1);
		verify(records).deleteWithQuota(record(1, 7));
		verify(notifier).unindex(1, 7);
	}

	@Test
	void deleteFolderUnindexesEachFile() {
		Record fileA = record(1, 7);
		Record fileB = new Record(2, 7, 4L, "b.txt", 5, "text/plain", "def", "users/7/k2", NOW);
		when(folders.findFolderOwner(4)).thenReturn(7L);
		when(folders.descendantIds(4)).thenReturn(List.of(4L, 5L));
		when(records.filesInFolders(List.of(4L, 5L))).thenReturn(List.of(fileA, fileB));
		service.deleteFolder(7, 4);
		verify(notifier).unindex(1, 7);
		verify(notifier).unindex(2, 7);
	}

	@Test
	void readContentTruncatesByLimit() {
		String content = "line1\nline2\nline3\n";
		when(records.find(1)).thenReturn(record(1, 7));
		when(objects.get("users/7/k")).thenReturn(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
		ContentView view = service.readContent(7, 1, 1, 2);
		assertEquals("line1\nline2", view.content());
		assertEquals(4, view.totalLines());
		assertTrue(view.truncated());
	}

	@Test
	void readContentOffsetPastEnd() {
		when(records.find(1)).thenReturn(record(1, 7));
		when(objects.get("users/7/k")).thenReturn(new ByteArrayInputStream("a\nb\n".getBytes(StandardCharsets.UTF_8)));
		ContentView view = service.readContent(7, 1, 10, null);
		assertEquals(3, view.totalLines());
		assertEquals("", view.content());
	}

	@Test
	void downloadDeniesForeignOwner() {
		when(records.find(1)).thenReturn(record(1, 8));
		assertThrows(Errors.AccessDenied.class, () -> service.download(7, 1));
	}

	@Test
	void textPolicy() {
		assertTrue(TextPolicy.isTextFile("README.md"));
		assertTrue(TextPolicy.isTextFile("app.go"));
		assertFalse(TextPolicy.isTextFile("photo.png"));
		assertFalse(TextPolicy.isTextFile("noext"));
		assertFalse(TextPolicy.isTextFile("trailing."));
	}

}