package com.clouddrive.multipart;

import com.clouddrive.auth.ProfileCache;
import com.clouddrive.common.Errors;
import com.clouddrive.file.Draft;
import com.clouddrive.file.KeyGenerator;
import com.clouddrive.file.Notifier;
import com.clouddrive.file.QuotaReader;
import com.clouddrive.file.Record;
import com.clouddrive.file.Repository;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 分块上传用例，对应 Go multipart.Service。
 */
@org.springframework.stereotype.Service
public class MultipartService {

	private final Metadata metadata;

	private final ObjectStore objects;

	private final FolderOwner folders;

	private final KeyGenerator keys;

	private final Repository records;

	private final QuotaReader quota;

	private final Notifier notifier;

	private final ProfileCache profiles;

	private final Duration ttl = Duration.ofHours(24);

	public MultipartService(Metadata metadata, ObjectStore objects, FolderOwner folders, KeyGenerator keys,
			Repository records, QuotaReader quota, Notifier notifier, ProfileCache profiles) {
		this.metadata = metadata;
		this.objects = objects;
		this.folders = folders;
		this.keys = keys;
		this.records = records;
		this.quota = quota;
		this.notifier = notifier;
		this.profiles = profiles;
	}

	public Meta init(long ownerId, String name, String mime, long size, Long folderId, String md5, long chunkSize) {
		if (folderId != null && folderId != 0) {
			long folderOwner = folders.findFolder(folderId);
			if (folderOwner != ownerId) {
				throw new Errors.AccessDenied("access denied");
			}
		}
		else {
			folderId = null;
		}
		int total = MultipartPolicy.totalChunks(size, chunkSize);
		QuotaReader.Remaining rem = quota.remaining(ownerId);
		if (!rem.ok() || size > rem.bytes()) {
			throw new Errors.StorageExceeded("storage limit exceeded");
		}
		String key = keys.newKey(ownerId);
		String uploadId = objects.createMultipart(key, mime);
		Meta meta = new Meta(ownerId, name, size, mime, folderId, md5, chunkSize, total, key, uploadId, rem.bytes());
		try {
			metadata.save(uploadId, meta, ttl);
		}
		catch (RuntimeException e) {
			objects.abortMultipart(key, uploadId);
			throw e;
		}
		return meta;
	}

	public List<Integer> uploadPart(String uploadId, long ownerId, int index, InputStream body, long size) {
		Meta meta = metadata.get(uploadId);
		if (meta.ownerId() != ownerId) {
			throw new Errors.AccessDenied("access denied");
		}
		if (!MultipartPolicy.validIndex(index, meta.totalChunks())) {
			throw new IllegalArgumentException("part index out of range");
		}
		if (!MultipartPolicy.validPartSize(index, meta.totalChunks(), size)) {
			throw new Errors.PartTooSmall("non-final part must be at least 5 MiB");
		}
		String etag = objects.uploadPart(meta.objectKey(), meta.uploadId(), index + 1, body, size);
		metadata.savePart(uploadId, index, etag, ttl);
		return metadata.receivedParts(uploadId);
	}

	public Record complete(String uploadId, long ownerId) {
		Meta meta = metadata.get(uploadId);
		if (meta.ownerId() != ownerId) {
			throw new Errors.AccessDenied("access denied");
		}
		List<Integer> parts = metadata.receivedParts(uploadId);
		if (!MultipartPolicy.contiguous(parts, meta.totalChunks())) {
			throw new Errors.Incomplete("incomplete parts: " + parts.size() + "/" + meta.totalChunks());
		}
		QuotaReader.Remaining rem = quota.remaining(ownerId);
		if (!rem.ok() || meta.size() > rem.bytes()) {
			throw new Errors.StorageExceeded("storage limit exceeded");
		}
		List<Part> completeParts = new ArrayList<>(meta.totalChunks());
		for (int i = 0; i < meta.totalChunks(); i++) {
			String etag = metadata.partEtag(uploadId, i);
			if (etag == null || etag.isEmpty()) {
				throw new IllegalStateException("missing etag for part " + i);
			}
			completeParts.add(new Part(i + 1, etag));
		}
		try {
			objects.completeMultipart(meta.objectKey(), meta.uploadId(), completeParts);
		}
		catch (RuntimeException e) {
			objects.abortMultipart(meta.objectKey(), meta.uploadId());
			metadata.delete(uploadId);
			throw e;
		}
		long actual;
		try {
			actual = objects.headSize(meta.objectKey());
		}
		catch (RuntimeException e) {
			objects.delete(meta.objectKey());
			metadata.delete(uploadId);
			throw e;
		}
		if (actual != meta.size()) {
			objects.delete(meta.objectKey());
			metadata.delete(uploadId);
			throw new Errors.SizeMismatch("uploaded bytes mismatch declared size");
		}
		Record record;
		try {
			record = records.createWithQuota(new Draft(ownerId, meta.size(), meta.folderId(), meta.name(),
					meta.mimeType(), meta.md5(), meta.objectKey()));
		}
		catch (RuntimeException e) {
			objects.delete(meta.objectKey());
			metadata.delete(uploadId);
			throw e;
		}
		metadata.delete(uploadId);
		if (profiles != null) {
			try {
				profiles.delete(record.ownerId());
			}
			catch (RuntimeException ignored) {
				// 缓存失效失败不阻塞主流程
			}
		}
		notifier.reindex(record.id(), record.ownerId());
		return record;
	}

	public void abort(String uploadId, long ownerId) {
		Meta meta = metadata.get(uploadId);
		if (meta.ownerId() != ownerId) {
			throw new Errors.AccessDenied("access denied");
		}
		objects.abortMultipart(meta.objectKey(), meta.uploadId());
		metadata.delete(uploadId);
	}

}