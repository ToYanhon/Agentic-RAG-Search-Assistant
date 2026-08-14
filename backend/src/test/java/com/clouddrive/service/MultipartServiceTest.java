package com.clouddrive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.clouddrive.common.AppException;
import com.clouddrive.entity.FileRecord;
import com.clouddrive.entity.User;
import com.clouddrive.repository.FileRepository;
import com.clouddrive.repository.UserRepository;
import com.clouddrive.service.AgentNotifier;
import com.clouddrive.service.CacheService;
import com.clouddrive.storage.MinioStorage;

/**
 * MultipartService 测试：字节校验（B5）+ 配额原子预占（D1）+ init 预检（D3）+ 重名重试（D4）+ 建索引通知（D8）。
 */
class MultipartServiceTest {

    private FileRepository fileRepo;
    private UserRepository userRepo;
    private MinioStorage storage;
    private StringRedisTemplate redis;
    private HashOperations<String, Object, Object> hashOps;
    private FileService fileService;
    private AgentNotifier notifier;
    private MultipartService svc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        fileRepo = mock(FileRepository.class);
        userRepo = mock(UserRepository.class);
        storage = mock(MinioStorage.class);
        redis = mock(StringRedisTemplate.class);
        hashOps = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashOps);
        fileService = mock(FileService.class);
        notifier = mock(AgentNotifier.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        svc = new MultipartService(fileRepo, userRepo, storage, redis, fileService, mock(CacheService.class),
                notifier, txManager);
    }

    private Map<Object, Object> meta(long size, int totalChunks) {
        Map<Object, Object> m = new HashMap<>();
        m.put("owner_id", "1");
        m.put("name", "a.bin");
        m.put("size", String.valueOf(size));
        m.put("mime_type", "application/octet-stream");
        m.put("folder_id", "0");
        m.put("md5", "");
        m.put("chunk_size", String.valueOf(size));
        m.put("total_chunks", String.valueOf(totalChunks));
        m.put("object_key", "users/1/1-a.bin");
        m.put("upload_id", "up-1");
        return m;
    }

    private void happyPath(long declaredSize, int totalChunks, String... partKeys) {
        when(hashOps.entries("multipart:u1")).thenReturn(meta(declaredSize, totalChunks));
        when(hashOps.keys("multipart:u1:parts")).thenReturn(Set.of(partKeys));
        for (String k : partKeys) {
            when(hashOps.get("multipart:u1:parts", k)).thenReturn("etag-" + k);
        }
        User user = new User();
        user.setId(1L);
        user.setStorageUsed(0L);
        user.setStorageLimit(1_073_741_824L);
        when(userRepo.findById(1L)).thenReturn(Optional.of(user));
        when(userRepo.tryAddStorageUsed(anyLong(), anyLong())).thenReturn(1);
        when(fileService.uniqueName(anyLong(), any(), anyString(), anyLong())).thenReturn("a.bin");
        when(fileRepo.save(any(FileRecord.class))).thenAnswer(inv -> {
            FileRecord r = inv.getArgument(0);
            r.setId(82L);
            return r;
        });
    }

    @Test
    void completeRejectsSizeMismatch() {
        long declared = 1_048_576L; // 声明 1MB
        happyPath(declared, 1, "0");
        when(storage.headObjectSize("users/1/1-a.bin")).thenReturn(10_000_000L); // 实际 ~10MB

        assertThatThrownBy(() -> svc.complete("u1", 1L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("mismatch");
        verify(storage).delete("users/1/1-a.bin");
        verify(fileRepo, never()).save(any());
        verify(userRepo, never()).addStorageUsed(anyLong(), anyLong());
    }

    @Test
    void completeRejectsWhenHeadFails() {
        happyPath(1_048_576L, 1, "0");
        when(storage.headObjectSize("users/1/1-a.bin")).thenThrow(new RuntimeException("s3 down"));

        assertThatThrownBy(() -> svc.complete("u1", 1L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("complete failed");
        verify(storage).delete("users/1/1-a.bin");
        verify(fileRepo, never()).save(any());
    }

    @Test
    void completeAcceptsWhenSizeMatches() {
        long declared = 1_048_576L;
        happyPath(declared, 1, "0");
        when(storage.headObjectSize("users/1/1-a.bin")).thenReturn(declared);

        FileRecord out = svc.complete("u1", 1L);

        assertThat(out.getSize()).isEqualTo(declared);
        assertThat(out.getFolderId()).isNull();
        // D1：配额以原子预占为准；D8：完成即通知建索引
        verify(userRepo).tryAddStorageUsed(1L, declared);
        verify(userRepo, never()).addStorageUsed(anyLong(), anyLong());
        verify(notifier).notifyReindex(82L, 1L);
        verify(storage, never()).delete(anyString());
    }

    // ---------- D3：init 配额预检 ----------

    @Test
    void initRejectsWhenQuotaInsufficient() {
        User user = new User();
        user.setId(1L);
        user.setStorageUsed(900L);
        user.setStorageLimit(1000L);
        when(userRepo.findById(1L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> svc.init(1L, "a.bin", "application/octet-stream", 200L, null, "", 1024 * 1024L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("storage limit");
        verify(storage, never()).createMultipartUpload(anyString(), anyString());
    }

    @Test
    void initReturnsRemaining() {
        User user = new User();
        user.setId(1L);
        user.setStorageUsed(400L);
        user.setStorageLimit(1000L);
        when(userRepo.findById(1L)).thenReturn(Optional.of(user));
        when(storage.createMultipartUpload(anyString(), anyString())).thenReturn("up-1");

        MultipartService.MultipartMeta m = svc.init(1L, "a.bin", "application/octet-stream", 100L, null, "", 1024 * 1024L);

        assertThat(m.getRemaining()).isEqualTo(600L);
        assertThat(m.getUploadId()).isEqualTo("up-1");
    }

    // ---------- D4：complete 落库冲突换名重试 ----------

    @Test
    void completeRetriesOnceOnNameConflict() {
        long declared = 1_048_576L;
        happyPath(declared, 1, "0");
        when(storage.headObjectSize("users/1/1-a.bin")).thenReturn(declared);
        AtomicInteger saveCalls = new AtomicInteger();
        when(fileRepo.save(any(FileRecord.class))).thenAnswer(inv -> {
            FileRecord r = inv.getArgument(0);
            r.setId(82L);
            if (saveCalls.incrementAndGet() == 1) {
                throw new DataIntegrityViolationException("duplicate name");
            }
            return r;
        });
        AtomicInteger un = new AtomicInteger();
        when(fileService.uniqueName(anyLong(), any(), anyString(), anyLong())).thenAnswer(inv ->
                un.incrementAndGet() == 1 ? "a.bin" : "a(1).bin");

        FileRecord out = svc.complete("u1", 1L);

        assertThat(out.getName()).isEqualTo("a(1).bin");
        verify(fileRepo, times(2)).save(any(FileRecord.class));
        verify(userRepo, times(2)).tryAddStorageUsed(anyLong(), anyLong());
        verify(notifier).notifyReindex(82L, 1L);
    }
}
