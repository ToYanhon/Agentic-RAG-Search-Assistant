package com.clouddrive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.clouddrive.common.AppException;
import com.clouddrive.entity.FileRecord;
import com.clouddrive.entity.User;
import com.clouddrive.repository.FileRepository;
import com.clouddrive.repository.UserRepository;
import com.clouddrive.storage.MinioStorage;

/**
 * MultipartService 分块上传字节校验测试（B5）：
 * complete 必须实测合并后对象字节数与声明 size 一致，否则拒绝且不建记录、不扣配额。
 */
class MultipartServiceTest {

    private FileRepository fileRepo;
    private UserRepository userRepo;
    private MinioStorage storage;
    private StringRedisTemplate redis;
    private HashOperations<String, Object, Object> hashOps;
    private FileService fileService;
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
        svc = new MultipartService(fileRepo, userRepo, storage, redis, fileService, mock(CacheService.class));
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
        when(fileService.uniqueName(anyLong(), any(), anyString(), anyLong())).thenReturn("a.bin");
        when(fileRepo.save(any(FileRecord.class))).thenAnswer(inv -> inv.getArgument(0));
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
        verify(userRepo).addStorageUsed(1L, declared);
        verify(storage, never()).delete(anyString());
    }
}
