package com.clouddrive.multipart;

import com.clouddrive.auth.ProfileCache;
import com.clouddrive.common.Errors;
import com.clouddrive.file.Draft;
import com.clouddrive.file.KeyGenerator;
import com.clouddrive.file.Notifier;
import com.clouddrive.file.QuotaReader;
import com.clouddrive.file.Record;
import com.clouddrive.file.Repository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MultipartServiceTest {

	private final Metadata metadata = mock(Metadata.class);

	private final com.clouddrive.multipart.ObjectStore objects = mock(com.clouddrive.multipart.ObjectStore.class);

	private final FolderOwner folders = mock(FolderOwner.class);

	private final KeyGenerator keys = mock(KeyGenerator.class);

	private final Repository records = mock(Repository.class);

	private final QuotaReader quota = mock(QuotaReader.class);

	private final Notifier notifier = mock(Notifier.class);

	private final ProfileCache profiles = mock(ProfileCache.class);

	private final MultipartService service = new MultipartService(metadata, objects, folders, keys, records, quota,
			notifier, profiles);

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 16, 12, 0, 0);

	private Meta meta() {
		return new Meta(7, "big.bin", 10L * 1024 * 1024, "application/octet-stream", null, "md5", 5L * 1024 * 1024, 2,
				"users/7/k", "upload-1", 1000);
	}

	@BeforeEach
	void setUp() {
		when(keys.newKey(7)).thenReturn("users/7/k");
		when(metadata.get("upload-1")).thenReturn(meta());
	}

	private static final long BIG_QUOTA = 100L * 1024 * 1024;

	@Test
	void initRejectsForeignFolder() {
		when(folders.findFolder(2)).thenReturn(8L);
		assertThrows(Errors.AccessDenied.class,
				() -> service.init(7, "a.txt", "text/plain", 10, 2L, "md5", 5 * 1024 * 1024));
	}

	@Test
	void initQuotaExceeded() {
		when(quota.remaining(7)).thenReturn(new QuotaReader.Remaining(5, true));
		assertThrows(Errors.StorageExceeded.class,
				() -> service.init(7, "a.txt", "text/plain", 100, null, "md5", 5 * 1024 * 1024));
	}

	@Test
	void initSavesMetadata() {
		when(quota.remaining(7)).thenReturn(new QuotaReader.Remaining(BIG_QUOTA, true));
		when(objects.createMultipart("users/7/k", "application/octet-stream")).thenReturn("upload-1");
		Meta meta = service.init(7, "big.bin", "application/octet-stream", 10L * 1024 * 1024, null, "md5",
				5L * 1024 * 1024);
		assertEquals("upload-1", meta.uploadId());
		assertEquals(2, meta.totalChunks());
		verify(metadata).save(anyString(), any(Meta.class), any());
	}

	@Test
	void uploadPartNonOwnerDenied() {
		assertThrows(Errors.AccessDenied.class,
				() -> service.uploadPart("upload-1", 8, 0, new ByteArrayInputStream(new byte[5]), 5));
	}

	@Test
	void uploadPartIndexOutOfRange() {
		assertThrows(IllegalArgumentException.class, () -> service.uploadPart("upload-1", 7, 5,
				new ByteArrayInputStream(new byte[5 * 1024 * 1024]), 5 * 1024 * 1024));
	}

	@Test
	void uploadPartTooSmall() {
		assertThrows(Errors.PartTooSmall.class,
				() -> service.uploadPart("upload-1", 7, 0, new ByteArrayInputStream(new byte[1024]), 1024));
	}

	@Test
	void uploadPartSuccess() {
		when(objects.uploadPart(eq("users/7/k"), eq("upload-1"), eq(1), any(InputStream.class), eq(5L * 1024 * 1024)))
			.thenReturn("etag-1");
		when(metadata.receivedParts("upload-1")).thenReturn(List.of(0));
		List<Integer> received = service.uploadPart("upload-1", 7, 0,
				new ByteArrayInputStream(new byte[5 * 1024 * 1024]), 5 * 1024 * 1024);
		assertEquals(List.of(0), received);
		verify(metadata).savePart(eq("upload-1"), eq(0), eq("etag-1"), any());
	}

	@Test
	void completeIncompleteParts() {
		when(metadata.receivedParts("upload-1")).thenReturn(List.of(0));
		assertThrows(Errors.Incomplete.class, () -> service.complete("upload-1", 7));
	}

	@Test
	void completeSuccessCreatesRecord() {
		when(metadata.receivedParts("upload-1")).thenReturn(List.of(0, 1));
		when(quota.remaining(7)).thenReturn(new QuotaReader.Remaining(BIG_QUOTA, true));
		when(metadata.partEtag("upload-1", 0)).thenReturn("etag-0");
		when(metadata.partEtag("upload-1", 1)).thenReturn("etag-1");
		when(objects.headSize("users/7/k")).thenReturn(10L * 1024 * 1024);
		when(records.createWithQuota(any(Draft.class))).thenReturn(new Record(9, 7, null, "big.bin", 10L * 1024 * 1024,
				"application/octet-stream", "md5", "users/7/k", NOW));
		Record record = service.complete("upload-1", 7);
		assertEquals(9, record.id());
		verify(objects).completeMultipart(anyString(), anyString(), any());
		verify(metadata).delete("upload-1");
		verify(notifier).reindex(9, 7);
	}

	@Test
	void completeSizeMismatch() {
		when(metadata.receivedParts("upload-1")).thenReturn(List.of(0, 1));
		when(quota.remaining(7)).thenReturn(new QuotaReader.Remaining(BIG_QUOTA, true));
		when(metadata.partEtag("upload-1", 0)).thenReturn("etag-0");
		when(metadata.partEtag("upload-1", 1)).thenReturn("etag-1");
		when(objects.headSize("users/7/k")).thenReturn(999L);
		assertThrows(Errors.SizeMismatch.class, () -> service.complete("upload-1", 7));
		verify(objects).delete("users/7/k");
	}

}