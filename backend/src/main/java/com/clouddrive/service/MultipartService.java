package com.clouddrive.service;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.clouddrive.common.AppException;
import com.clouddrive.entity.FileRecord;
import com.clouddrive.entity.User;
import com.clouddrive.repository.FileRepository;
import com.clouddrive.repository.UserRepository;
import com.clouddrive.storage.MinioStorage;
import software.amazon.awssdk.services.s3.model.CompletedPart;

import lombok.Data;

/**
 * 分块上传（对齐 Go multipartService）：基于 MinIO 原生 S3 multipart，
 * 元数据与已收分块 etag 存 Redis Hash（24h TTL，每次收块刷新）。
 */
@Service
public class MultipartService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MultipartService.class);

    private static final Duration TTL = Duration.ofHours(24);

    private final FileRepository fileRepo;
    private final UserRepository userRepo;
    private final MinioStorage storage;
    private final StringRedisTemplate redis;
    private final FileService fileService;
    private final CacheService cache;

    public MultipartService(FileRepository fileRepo, UserRepository userRepo,
                            MinioStorage storage, StringRedisTemplate redis,
                            FileService fileService, CacheService cache) {
        this.fileRepo = fileRepo;
        this.userRepo = userRepo;
        this.storage = storage;
        this.redis = redis;
        this.fileService = fileService;
        this.cache = cache;
    }

    @Data
    public static class MultipartMeta {
        private Long ownerId;
        private String name;
        private long size;
        private String mimeType;
        private Long folderId;
        private String md5;
        private long chunkSize;
        private int totalChunks;
        private String objectKey;
        private String uploadId;
    }

    private static String key(String uid) {
        return "multipart:" + uid;
    }

    private static String partsKey(String uid) {
        return "multipart:" + uid + ":parts";
    }

    @Transactional
    public MultipartMeta init(Long ownerId, String name, String mimeType, long size,
                              Long folderId, String md5, long chunkSize) {
        Long folder = fileService.validateFolder(ownerId, folderId);
        int total = (int) ((size + chunkSize - 1) / chunkSize);
        if (total == 0) {
            total = 1;
        }
        String objectKey = "users/" + ownerId + "/" + System.nanoTime() + "-" + name;
        String uploadId;
        try {
            uploadId = storage.createMultipartUpload(objectKey, mimeType);
        } catch (Exception e) {
            log.warn("multipart init failed", e);
            throw AppException.internal("create multipart failed");
        }
        Map<String, String> meta = new HashMap<>();
        meta.put("owner_id", String.valueOf(ownerId));
        meta.put("name", name);
        meta.put("size", String.valueOf(size));
        meta.put("mime_type", mimeType == null ? "" : mimeType);
        meta.put("folder_id", folder == null ? "0" : String.valueOf(folder));
        meta.put("md5", md5 == null ? "" : md5);
        meta.put("chunk_size", String.valueOf(chunkSize));
        meta.put("total_chunks", String.valueOf(total));
        meta.put("object_key", objectKey);
        meta.put("upload_id", uploadId);
        redis.opsForHash().putAll(key(uploadId), meta);
        redis.expire(key(uploadId), TTL);
        redis.expire(partsKey(uploadId), TTL);

        MultipartMeta m = new MultipartMeta();
        m.setOwnerId(ownerId);
        m.setName(name);
        m.setSize(size);
        m.setMimeType(mimeType);
        m.setFolderId(folder);
        m.setMd5(md5);
        m.setChunkSize(chunkSize);
        m.setTotalChunks(total);
        m.setObjectKey(objectKey);
        m.setUploadId(uploadId);
        return m;
    }

    private MultipartMeta getMeta(String uid) {
        Map<Object, Object> raw = redis.opsForHash().entries(key(uid));
        if (raw.isEmpty()) {
            throw AppException.notFound("resource not found");
        }
        MultipartMeta m = new MultipartMeta();
        m.setOwnerId(Long.valueOf(str(raw, "owner_id")));
        m.setName(str(raw, "name"));
        m.setSize(Long.parseLong(str(raw, "size")));
        m.setMimeType(str(raw, "mime_type"));
        long fid = Long.parseLong(str(raw, "folder_id"));
        m.setFolderId(fid == 0 ? null : fid);
        m.setMd5(str(raw, "md5"));
        m.setChunkSize(Long.parseLong(str(raw, "chunk_size")));
        m.setTotalChunks(Integer.parseInt(str(raw, "total_chunks")));
        m.setObjectKey(str(raw, "object_key"));
        m.setUploadId(str(raw, "upload_id"));
        return m;
    }

    private static String str(Map<Object, Object> raw, String k) {
        Object v = raw.get(k);
        return v == null ? "" : v.toString();
    }

    /** 上传单个分块（index 从 0 起，对应 MinIO partNumber = index+1）；返回已收分块索引。 */
    public List<Integer> uploadPart(String uid, Long ownerId, int index, byte[] data) {
        MultipartMeta meta = getMeta(uid);
        if (!meta.getOwnerId().equals(ownerId)) {
            throw AppException.forbidden("access denied");
        }
        if (index < 0 || index >= meta.getTotalChunks()) {
            throw AppException.badRequest("part index out of range");
        }
        String etag;
        try {
            etag = storage.uploadPart(meta.getObjectKey(), meta.getUploadId(), index + 1,
                    new ByteArrayInputStream(data), data.length);
        } catch (Exception e) {
            log.warn("multipart uploadPart failed", e);
            throw AppException.internal("put part failed");
        }
        redis.opsForHash().put(partsKey(uid), String.valueOf(index), etag);
        redis.expire(key(uid), TTL);
        redis.expire(partsKey(uid), TTL);
        return receivedParts(uid);
    }

    private List<Integer> receivedParts(String uid) {
        Set<Object> keys = redis.opsForHash().keys(partsKey(uid));
        return keys.stream()
                .map(Object::toString)
                .map(Integer::parseInt)
                .sorted()
                .collect(Collectors.toList());
    }

    /** 校验分块齐全后合并，建 File 记录并扣减配额。 */
    @Transactional
    public FileRecord complete(String uid, Long ownerId) {
        MultipartMeta meta = getMeta(uid);
        if (!meta.getOwnerId().equals(ownerId)) {
            throw AppException.forbidden("access denied");
        }
        List<Integer> parts = receivedParts(uid);
        if (parts.size() != meta.getTotalChunks()) {
            throw AppException.badRequest("incomplete parts: " + parts.size() + "/" + meta.getTotalChunks());
        }
        for (int i = 0; i < meta.getTotalChunks(); i++) {
            if (i >= parts.size() || parts.get(i) != i) {
                throw AppException.badRequest("parts not contiguous");
            }
        }
        User user = userRepo.findById(ownerId)
                .orElseThrow(() -> AppException.internal("owner not found"));
        if (user.getStorageUsed() + meta.getSize() > user.getStorageLimit()) {
            throw AppException.storageExceeded("storage limit exceeded");
        }
        String name = fileService.uniqueName(ownerId, meta.getFolderId(), meta.getName(), 0L);

        List<CompletedPart> completeParts = new ArrayList<>();
        for (int i = 0; i < meta.getTotalChunks(); i++) {
            Object etag = redis.opsForHash().get(partsKey(uid), String.valueOf(i));
            if (etag == null || etag.toString().isEmpty()) {
                throw AppException.badRequest("missing etag for part " + i);
            }
            completeParts.add(CompletedPart.builder().partNumber(i + 1).eTag(etag.toString()).build());
        }
        try {
            storage.completeMultipartUpload(meta.getObjectKey(), meta.getUploadId(), completeParts);
        } catch (Exception e) {
            log.warn("multipart complete failed", e);
            throw AppException.internal("complete failed");
        }
        redis.delete(List.of(key(uid), partsKey(uid)));

        // 实测合并后对象字节数：客户端声明的 size 不可信，须与真实分块总和一致，
        // 否则（如声明 1MB 传 10MB 块）会绕过存储配额并污染元数据。
        long actual;
        try {
            actual = storage.headObjectSize(meta.getObjectKey());
        } catch (Exception e) {
            log.warn("multipart headObject failed, cleaning up uid={}", uid, e);
            try {
                storage.delete(meta.getObjectKey());
            } catch (Exception ignored) {
            }
            throw AppException.internal("complete failed");
        }
        if (actual != meta.getSize()) {
            log.warn("multipart size mismatch declared={} actual={} uid={}", meta.getSize(), actual, uid);
            try {
                storage.delete(meta.getObjectKey());
            } catch (Exception ignored) {
            }
            throw AppException.badRequest("uploaded bytes mismatch declared size");
        }

        FileRecord f = new FileRecord();
        f.setName(name);
        f.setSize(meta.getSize());
        f.setMimeType(meta.getMimeType());
        f.setMd5(meta.getMd5());
        f.setObjectKey(meta.getObjectKey());
        f.setFolderId(meta.getFolderId());
        f.setOwnerId(ownerId);
        try {
            fileRepo.save(f);
        } catch (Exception e) {
            try {
                storage.delete(meta.getObjectKey());
            } catch (Exception ignored) {
            }
            throw AppException.internal("create file record failed");
        }
        userRepo.addStorageUsed(ownerId, meta.getSize());
        cache.del(FileService.profileKey(ownerId), FileService.checksumKey(ownerId, meta.getMd5()));
        return f;
    }

    @Transactional
    public void abort(String uid, Long ownerId) {
        MultipartMeta meta = getMeta(uid);
        if (!meta.getOwnerId().equals(ownerId)) {
            throw AppException.forbidden("access denied");
        }
        try {
            storage.abortMultipartUpload(meta.getObjectKey(), meta.getUploadId());
        } catch (Exception e) {
            throw AppException.internal("abort multipart failed");
        }
        redis.delete(List.of(key(uid), partsKey(uid)));
    }
}
