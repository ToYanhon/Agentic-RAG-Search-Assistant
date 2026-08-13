package com.clouddrive.service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.clouddrive.common.AppException;
import com.clouddrive.entity.FileRecord;
import com.clouddrive.entity.Folder;
import com.clouddrive.entity.User;
import com.clouddrive.repository.FileRepository;
import com.clouddrive.repository.FolderRepository;
import com.clouddrive.repository.UserRepository;
import com.clouddrive.storage.MinioStorage;

/**
 * 文件业务（对齐 Go fileService）：直传/秒传/下载/删除(含级联)/重命名/移动/搜索/列表。
 * 关键不变量：秒传 owner 限定 + CopyObject 独立对象；删除/级联删除必发 agent unindex。
 */
@Service
public class FileService {

    private static final Duration CHECKSUM_CACHE_TTL = Duration.ofSeconds(60);
    private static final Pattern NUM_SUFFIX = Pattern.compile("\\((\\d+)\\)$");

    private final FileRepository fileRepo;
    private final FolderRepository folderRepo;
    private final UserRepository userRepo;
    private final MinioStorage storage;
    private final CacheService cache;
    private final AgentNotifier notifier;

    public FileService(FileRepository fileRepo, FolderRepository folderRepo,
            UserRepository userRepo, MinioStorage storage,
            CacheService cache, AgentNotifier notifier) {
        this.fileRepo = fileRepo;
        this.folderRepo = folderRepo;
        this.userRepo = userRepo;
        this.storage = storage;
        this.cache = cache;
        this.notifier = notifier;
    }

    // ---------- 上传 ----------

    @Transactional
    public FileRecord upload(Long ownerId, byte[] data, String originalName, Long folderId, String contentType) {
        User user = userRepo.findById(ownerId)
                .orElseThrow(() -> AppException.internal("owner not found"));
        if (user.getStorageUsed() + data.length > user.getStorageLimit()) {
            throw AppException.storageExceeded("storage limit exceeded");
        }
        String name = uniqueName(ownerId, folderId, originalName, 0L);
        String md5 = md5Hex(data);
        String objectKey = objectKey(ownerId, name);
        try {
            storage.upload(objectKey, new ByteArrayInputStream(data), data.length, contentType);
        } catch (Exception e) {
            throw AppException.internal("storage upload failed");
        }
        FileRecord f = new FileRecord();
        f.setName(name);
        f.setSize((long) data.length);
        f.setMimeType(contentType);
        f.setMd5(md5);
        f.setObjectKey(objectKey);
        f.setFolderId(folderId);
        f.setOwnerId(ownerId);
        try {
            fileRepo.save(f);
        } catch (Exception e) {
            try {
                storage.delete(objectKey);
            } catch (Exception ignored) {
            }
            throw AppException.internal("create file record failed");
        }
        userRepo.addStorageUsed(ownerId, data.length);
        cache.del(profileKey(ownerId), checksumKey(ownerId, md5));
        return f;
    }

    /** 秒传预检：MD5+大小查重，命中且源属于请求者本人时 CopyObject 到新 key 建记录。 */
    @Transactional
    public FileRecord checksumInstant(Long ownerId, String md5, String name, long size, Long folderId) {
        String key = checksumKey(ownerId, md5);
        var cached = cache.get(key, ChecksumVal.class);
        if (cached.isPresent() && !cached.get().isExists()) {
            return null;
        }
        FileRecord src = fileRepo.findFirstByMd5AndOwnerId(md5, ownerId).orElse(null);
        if (src == null) {
            cache.set(key, new ChecksumVal(false), CHECKSUM_CACHE_TTL);
            return null;
        }
        if (src.getSize() != size) {
            return null;
        }
        cache.set(key, new ChecksumVal(true), CHECKSUM_CACHE_TTL);

        User user = userRepo.findById(ownerId).orElseThrow(() -> AppException.internal("owner not found"));
        if (user.getStorageUsed() + size > user.getStorageLimit()) {
            throw AppException.storageExceeded("storage limit exceeded");
        }
        String unique = uniqueName(ownerId, folderId, name, 0L);
        String objectKey = objectKey(ownerId, unique);
        try {
            storage.copyObject(src.getObjectKey(), objectKey);
        } catch (Exception e) {
            throw AppException.internal("copy object failed");
        }
        FileRecord f = new FileRecord();
        f.setName(unique);
        f.setSize(size);
        f.setMimeType(src.getMimeType());
        f.setMd5(src.getMd5());
        f.setObjectKey(objectKey);
        f.setFolderId(folderId);
        f.setOwnerId(ownerId);
        try {
            fileRepo.save(f);
        } catch (Exception e) {
            try {
                storage.delete(objectKey);
            } catch (Exception ignored) {
            }
            throw AppException.internal("create file record failed");
        }
        userRepo.addStorageUsed(ownerId, size);
        cache.del(profileKey(ownerId));
        return f;
    }

    // ---------- 下载 ----------

    /** 下载（owner 校验）。 */
    public DownloadResult download(Long fileId, Long userId) {
        FileRecord f = fileRepo.findById(fileId)
                .orElseThrow(() -> AppException.notFound("resource not found"));
        if (!f.getOwnerId().equals(userId)) {
            throw AppException.forbidden("access denied");
        }
        return new DownloadResult(open(f), f);
    }

    /** 按 ID 直接下载，不校验 owner（公开分享用）。 */
    public DownloadResult downloadById(Long fileId) {
        FileRecord f = fileRepo.findById(fileId)
                .orElseThrow(() -> AppException.notFound("resource not found"));
        return new DownloadResult(open(f), f);
    }

    public record DownloadResult(InputStream stream, FileRecord file) {
    }

    private InputStream open(FileRecord f) {
        try {
            return storage.download(f.getObjectKey());
        } catch (Exception e) {
            throw AppException.internal("storage download failed");
        }
    }

    // ---------- 删除 ----------

    @Transactional
    public void delete(Long fileId, Long userId) {
        FileRecord f = fileRepo.findById(fileId)
                .orElseThrow(() -> AppException.notFound("resource not found"));
        if (!f.getOwnerId().equals(userId)) {
            throw AppException.forbidden("access denied");
        }
        try {
            storage.delete(f.getObjectKey());
        } catch (Exception e) {
            throw AppException.internal("storage delete failed");
        }
        fileRepo.deleteById(fileId);
        userRepo.addStorageUsed(userId, -f.getSize());
        cache.del(profileKey(userId), checksumKey(userId, f.getMd5()));
        // 删除即取消索引：异步通知 Agent 清理 Qdrant
        notifier.notifyUnindex(f.getId(), f.getOwnerId());
    }

    /** 级联删除多个文件夹下的全部文件（含 Agent unindex 通知）。 */
    @Transactional
    public void deleteByFolderIds(List<Long> folderIds) {
        List<FileRecord> files = fileRepo.findByFolderIdIn(folderIds);
        if (files.isEmpty()) {
            return;
        }
        List<String> keys = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        Map<Long, Long> sizes = new HashMap<>();
        for (FileRecord f : files) {
            keys.add(f.getObjectKey());
            ids.add(f.getId());
            sizes.merge(f.getOwnerId(), f.getSize(), Long::sum);
        }
        try {
            storage.deleteObjects(keys);
        } catch (Exception e) {
            throw AppException.internal("storage delete objects failed");
        }
        fileRepo.deleteAllById(ids);
        sizes.forEach(userRepo::addStorageUsed);
        List<String> delKeys = new ArrayList<>();
        for (FileRecord f : files) {
            delKeys.add(checksumKey(f.getOwnerId(), f.getMd5()));
            delKeys.add(profileKey(f.getOwnerId()));
            notifier.notifyUnindex(f.getId(), f.getOwnerId());
        }
        cache.del(delKeys.toArray(String[]::new));
    }

    // ---------- 重命名 / 移动 ----------

    @Transactional
    public void rename(Long fileId, Long userId, String name) {
        FileRecord f = fileRepo.findById(fileId)
                .orElseThrow(() -> AppException.notFound("resource not found"));
        if (!f.getOwnerId().equals(userId)) {
            throw AppException.forbidden("access denied");
        }
        String unique = uniqueName(userId, f.getFolderId(), name, fileId);
        fileRepo.updateName(fileId, unique);
    }

    @Transactional
    public void move(Long fileId, Long userId, long targetFolderId) {
        FileRecord f = fileRepo.findById(fileId)
                .orElseThrow(() -> AppException.notFound("resource not found"));
        if (!f.getOwnerId().equals(userId)) {
            throw AppException.forbidden("access denied");
        }
        Long target = null;
        if (targetFolderId != 0) {
            Folder folder = folderRepo.findById(targetFolderId)
                    .orElseThrow(() -> AppException.badRequest("target folder not found"));
            if (!folder.getOwnerId().equals(userId)) {
                throw AppException.forbidden("access denied");
            }
            target = targetFolderId;
        }
        String unique = uniqueName(userId, target, f.getName(), fileId);
        if (!unique.equals(f.getName())) {
            fileRepo.updateName(fileId, unique);
        }
        fileRepo.updateFolderId(fileId, target);
    }

    // ---------- 写内容（agent 工具用） ----------

    /** 覆盖已有文件内容（owner 限定）：写新对象 key 后更新记录（对齐秒传独立对象约定）。 */
    @Transactional
    public FileRecord overwriteContent(Long fileId, Long userId, String content) {
        FileRecord f = fileRepo.findById(fileId)
                .orElseThrow(() -> AppException.notFound("resource not found"));
        if (!f.getOwnerId().equals(userId)) {
            throw AppException.forbidden("access denied");
        }
        byte[] data = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        User user = userRepo.findById(userId).orElseThrow(() -> AppException.internal("owner not found"));
        if (user.getStorageUsed() + data.length > user.getStorageLimit()) {
            throw AppException.storageExceeded("storage limit exceeded");
        }
        String objectKey = objectKey(userId, f.getName());
        String mime = "text/plain; charset=utf-8";
        try {
            storage.upload(objectKey, new ByteArrayInputStream(data), data.length, mime);
        } catch (Exception e) {
            throw AppException.internal("storage upload failed");
        }
        String oldKey = f.getObjectKey();
        long oldSize = f.getSize();
        f.setSize((long) data.length);
        f.setMimeType(mime);
        f.setMd5(md5Hex(data));
        f.setObjectKey(objectKey);
        try {
            fileRepo.save(f);
        } catch (Exception e) {
            try {
                storage.delete(objectKey);
            } catch (Exception ignored) {
            }
            throw AppException.internal("update file record failed");
        }
        try {
            storage.delete(oldKey);
        } catch (Exception ignored) {
        }
        userRepo.addStorageUsed(userId, data.length - oldSize);
        cache.del(profileKey(userId), checksumKey(userId, f.getMd5()));
        // 内容已变更：异步通知 Agent 重建索引（尽力而为，防止检索命中过期内容）
        notifier.notifyReindex(f.getId(), f.getOwnerId());
        return f;
    }

    /** 文本内容按行读取（owner 校验；非文本拒绝；offset/limit 行切片）。 */
    public ContentView readContent(Long fileId, Long userId, int offset, Integer limit) {
        FileRecord f = fileRepo.findById(fileId)
                .orElseThrow(() -> AppException.notFound("resource not found"));
        if (!f.getOwnerId().equals(userId)) {
            throw AppException.forbidden("access denied");
        }
        if (!isTextName(f.getName())) {
            throw AppException.badRequest("not a text file");
        }
        String text;
        try (InputStream in = storage.download(f.getObjectKey())) {
            text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw AppException.internal("storage read failed");
        }
        String[] lines = text.split("\n", -1);
        int total = lines.length;
        int from = Math.max(1, offset);
        if (from > total) {
            return new ContentView("", total, false);
        }
        int to = limit != null && limit > 0 ? Math.min(total, from + limit - 1) : total;
        String joined = String.join("\n", java.util.Arrays.copyOfRange(lines, from - 1, to));
        boolean truncated = to < total;
        return new ContentView(joined, total, truncated);
    }

    /** 文本内容读取视图。 */
    public record ContentView(String content, int totalLines, boolean truncated) {
    }

    private static final java.util.Set<String> TEXT_EXTS = java.util.Set.of(
            "txt", "md", "markdown", "csv", "json", "xml", "yml", "yaml", "ini", "log",
            "js", "ts", "tsx", "jsx", "html", "css", "py", "go", "java", "c", "h", "cpp",
            "sh", "bat", "sql");

    private static boolean isTextName(String name) {
        if (name == null) {
            return false;
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        return TEXT_EXTS.contains(name.substring(dot + 1).toLowerCase());
    }

    // ---------- 查询 ----------

    public List<FileRecord> search(Long ownerId, String query, int page, int pageSize) {
        PageRequest pr = PageRequest.of(page - 1, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        return fileRepo.findByOwnerIdAndNameContainingOrderByCreatedAtDesc(ownerId, query, pr);
    }

    public long countSearch(Long ownerId, String query) {
        return fileRepo.countByOwnerIdAndNameContaining(ownerId, query);
    }

    public List<FileRecord> listByOwner(Long ownerId, int page, int pageSize) {
        PageRequest pr = PageRequest.of(page - 1, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        return fileRepo.findByOwnerIdOrderByCreatedAtDesc(ownerId, pr);
    }

    public long countOwner(Long ownerId) {
        return fileRepo.countByOwnerId(ownerId);
    }

    public List<FileRecord> listByFolder(Long folderId, Long ownerId) {
        return fileRepo.findByFolderIdOrderByCreatedAtDesc(folderId);
    }

    public FileRecord getFileById(Long id) {
        return fileRepo.findById(id).orElseThrow(() -> AppException.notFound("resource not found"));
    }

    // ---------- 工具 ----------

    /** 同文件夹内不重名：自动追加/递增序号（foo.txt → foo(1).txt）。 */
    public String uniqueName(Long ownerId, Long folderId, String name, Long excludeId) {
        if (!fileRepo.nameTaken(ownerId, folderId, name, excludeId)) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot) : "";
        String stem = dot >= 0 ? name.substring(0, dot) : name;
        int start = 1;
        Matcher m = NUM_SUFFIX.matcher(stem);
        if (m.find()) {
            try {
                int n = Integer.parseInt(m.group(1));
                stem = stem.substring(0, m.start());
                start = n + 1;
            } catch (NumberFormatException ignored) {
            }
        }
        for (int i = start;; i++) {
            String candidate = stem + "(" + i + ")" + ext;
            if (!fileRepo.nameTaken(ownerId, folderId, candidate, excludeId)) {
                return candidate;
            }
        }
    }

    private String objectKey(Long ownerId, String name) {
        return "users/" + ownerId + "/" + System.nanoTime() + "-" + name;
    }

    private static String md5Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(data);
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    public static String profileKey(Long userId) {
        return "user_profile:" + userId;
    }

    public static String checksumKey(Long userId, String md5) {
        return "checksum:" + userId + ":" + md5;
    }

    /** 秒传查重缓存值（只记录该 MD5 是否存在的事实，负缓存防打库）。 */
    public static class ChecksumVal {
        private boolean exists;

        public ChecksumVal() {
        }

        public ChecksumVal(boolean exists) {
            this.exists = exists;
        }

        public boolean isExists() {
            return exists;
        }

        public void setExists(boolean exists) {
            this.exists = exists;
        }
    }
}
