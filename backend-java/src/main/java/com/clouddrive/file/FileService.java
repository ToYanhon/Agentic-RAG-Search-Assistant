package com.clouddrive.file;

import com.clouddrive.auth.ProfileCache;
import com.clouddrive.common.Errors;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/**
 * 文件生命周期用例，对应 Go file.Service。
 */
@org.springframework.stereotype.Service
public class FileService {

	private final Repository records;

	private final FolderTree folders;

	private final QuotaReader quota;

	private final ObjectStore objects;

	private final KeyGenerator keys;

	private final Notifier notifier;

	private final ProfileCache profiles;

	private final ChecksumCache checksums;

	public FileService(Repository records, FolderTree folders, QuotaReader quota, ObjectStore objects,
			KeyGenerator keys, Notifier notifier, ProfileCache profiles, ChecksumCache checksums) {
		this.records = records;
		this.folders = folders;
		this.quota = quota;
		this.objects = objects;
		this.keys = keys;
		this.notifier = notifier;
		this.profiles = profiles;
		this.checksums = checksums;
	}

	public Record upload(long ownerId, Long folderId, String name, String mime, byte[] data) {
		if (name == null || name.isEmpty()) {
			throw new IllegalArgumentException("name required");
		}
		folderId = normalize(folderId);
		if (folderId != null) {
			long folderOwner = folders.findFolderOwner(folderId);
			if (folderOwner != ownerId) {
				throw new Errors.AccessDenied("access denied");
			}
		}
		QuotaReader.Remaining rem = quota.remaining(ownerId);
		if (!rem.ok() || data.length > rem.bytes()) {
			throw new Errors.StorageExceeded("storage limit exceeded");
		}
		String key = keys.newKey(ownerId);
		try {
			objects.put(key, mime, new ByteArrayInputStream(data), data.length);
		}
		catch (RuntimeException e) {
			throw e;
		}
		String digest = md5Hex(data);
		Record record;
		try {
			record = records.createWithQuota(new Draft(ownerId, data.length, folderId, name, mime, digest, key));
		}
		catch (RuntimeException e) {
			objects.delete(key);
			throw e;
		}
		invalidateProfile(record.ownerId());
		invalidateChecksum(record.ownerId(), digest);
		notifier.reindex(record.id(), record.ownerId());
		return record;
	}

	public InstantResult checksumInstant(long ownerId, String md5, String name, long size, Long folderId) {
		ChecksumCache.ChecksumResult cached = checksums.get(ownerId, md5);
		if (cached.hit() && !cached.exists()) {
			return InstantResult.miss();
		}
		Record source;
		try {
			source = records.findByMd5Owner(ownerId, md5);
		}
		catch (Errors.NotFound e) {
			checksums.set(ownerId, md5, false, Duration.ofMinutes(1));
			return InstantResult.miss();
		}
		if (source.size() != size) {
			return InstantResult.miss();
		}
		checksums.set(ownerId, md5, true, Duration.ofMinutes(1));
		folderId = normalize(folderId);
		if (folderId != null) {
			long folderOwner = folders.findFolderOwner(folderId);
			if (folderOwner != ownerId) {
				throw new Errors.AccessDenied("access denied");
			}
		}
		QuotaReader.Remaining rem = quota.remaining(ownerId);
		if (!rem.ok() || size > rem.bytes()) {
			throw new Errors.StorageExceeded("storage limit exceeded");
		}
		String key = keys.newKey(ownerId);
		try {
			objects.copy(source.objectKey(), key);
		}
		catch (RuntimeException e) {
			throw e;
		}
		Record record;
		try {
			record = records.createWithQuota(new Draft(ownerId, size, folderId, name, source.mimeType(), md5, key));
		}
		catch (RuntimeException e) {
			objects.delete(key);
			throw e;
		}
		invalidateProfile(record.ownerId());
		invalidateChecksum(record.ownerId(), md5);
		notifier.reindex(record.id(), record.ownerId());
		return InstantResult.hit(record);
	}

	public Record createTextFile(long ownerId, String name, String content, Long folderId) {
		if (name == null || name.isEmpty()) {
			throw new IllegalArgumentException("name required");
		}
		return upload(ownerId, folderId, name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
	}

	public Record overwriteContent(long ownerId, long id, String content) {
		Record record = records.find(id);
		if (record.ownerId() != ownerId) {
			throw new Errors.AccessDenied("access denied");
		}
		if (!TextPolicy.isTextFile(record.name())) {
			throw new Errors.NotTextFile("not a text file");
		}
		byte[] data = content.getBytes(StandardCharsets.UTF_8);
		QuotaReader.Remaining rem = quota.remaining(ownerId);
		long projected = rem.bytes() + record.size();
		if (!rem.ok() || data.length > projected) {
			throw new Errors.StorageExceeded("storage limit exceeded");
		}
		String key = keys.newKey(ownerId);
		objects.put(key, "text/plain; charset=utf-8", new ByteArrayInputStream(data), data.length);
		Record updated = new Record(record.id(), record.ownerId(), record.folderId(), record.name(), data.length,
				"text/plain; charset=utf-8", md5Hex(data), key, record.createdAt());
		try {
			records.updateContent(updated, updated.size() - record.size(), record.objectKey());
		}
		catch (RuntimeException e) {
			objects.delete(key);
			throw e;
		}
		invalidateProfile(updated.ownerId());
		invalidateChecksum(updated.ownerId(), record.md5());
		invalidateChecksum(updated.ownerId(), updated.md5());
		notifier.reindex(updated.id(), updated.ownerId());
		return updated;
	}

	public ContentView readContent(long ownerId, long id, int offset, Integer limit) {
		Record record = records.find(id);
		if (record.ownerId() != ownerId) {
			throw new Errors.AccessDenied("access denied");
		}
		if (!TextPolicy.isTextFile(record.name())) {
			throw new Errors.NotTextFile("not a text file");
		}
		byte[] data;
		try (InputStream body = objects.get(record.objectKey())) {
			data = body.readAllBytes();
		}
		catch (java.io.IOException e) {
			throw new IllegalStateException("read content failed", e);
		}
		String text = new String(data, StandardCharsets.UTF_8);
		String[] lines = text.split("\n", -1);
		int from = Math.max(offset, 1);
		if (from > lines.length) {
			return new ContentView("", lines.length, false);
		}
		int to = lines.length;
		if (limit != null && limit > 0 && from + limit - 1 < to) {
			to = from + limit - 1;
		}
		String joined = String.join("\n", java.util.Arrays.copyOfRange(lines, from - 1, to));
		return new ContentView(joined, lines.length, to < lines.length);
	}

	public Download download(long ownerId, long id) {
		Record record = records.find(id);
		if (record.ownerId() != ownerId) {
			throw new Errors.AccessDenied("access denied");
		}
		return new Download(record, objects.get(record.objectKey()));
	}

	public Download downloadRange(long ownerId, long id, long offset, long length) {
		Record record = records.find(id);
		if (record.ownerId() != ownerId) {
			throw new Errors.AccessDenied("access denied");
		}
		return new Download(record, objects.getRange(record.objectKey(), offset, length));
	}

	public void delete(long ownerId, long id) {
		Record record = records.find(id);
		if (record.ownerId() != ownerId) {
			throw new Errors.AccessDenied("access denied");
		}
		records.deleteWithQuota(record);
		invalidateProfile(record.ownerId());
		invalidateChecksum(record.ownerId(), record.md5());
		notifier.unindex(record.id(), record.ownerId());
	}

	public void deleteFolder(long ownerId, long folderId) {
		long folderOwner = folders.findFolderOwner(folderId);
		if (folderOwner != ownerId) {
			throw new Errors.AccessDenied("access denied");
		}
		List<Long> folderIds = folders.descendantIds(folderId);
		List<Record> files = records.filesInFolders(folderIds);
		records.deleteFolderCascade(ownerId, folderIds, files);
		if (!files.isEmpty()) {
			invalidateProfile(ownerId);
			for (Record record : files) {
				invalidateChecksum(record.ownerId(), record.md5());
			}
		}
		for (Record record : files) {
			notifier.unindex(record.id(), record.ownerId());
		}
	}

	private void invalidateProfile(long ownerId) {
		if (profiles != null) {
			try {
				profiles.delete(ownerId);
			}
			catch (RuntimeException ignored) {
				// 缓存失效失败不阻塞主流程
			}
		}
	}

	private void invalidateChecksum(long ownerId, String md5) {
		if (checksums != null && md5 != null && !md5.isEmpty()) {
			try {
				checksums.delete(ownerId, md5);
			}
			catch (RuntimeException ignored) {
				// 缓存失效失败不阻塞主流程
			}
		}
	}

	private static Long normalize(Long folderId) {
		if (folderId == null || folderId == 0) {
			return null;
		}
		return folderId;
	}

	private static String md5Hex(byte[] data) {
		try {
			byte[] digest = MessageDigest.getInstance("MD5").digest(data);
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	public record InstantResult(boolean instant, Record record) {
		static InstantResult miss() {
			return new InstantResult(false, null);
		}

		static InstantResult hit(Record record) {
			return new InstantResult(true, record);
		}
	}

}