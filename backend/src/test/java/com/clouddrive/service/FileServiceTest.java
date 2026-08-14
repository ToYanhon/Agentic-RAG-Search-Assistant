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

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.clouddrive.common.AppException;
import com.clouddrive.entity.FileRecord;
import com.clouddrive.entity.Folder;
import com.clouddrive.entity.User;
import com.clouddrive.repository.FileRepository;
import com.clouddrive.repository.FolderRepository;
import com.clouddrive.repository.UserRepository;
import com.clouddrive.service.AgentNotifier;
import com.clouddrive.service.CacheService;
import com.clouddrive.storage.MinioStorage;

/**
 * FileService 单元测试：uniqueName 去重 + overwriteContent 覆盖写 + 配额原子预占（D1/D2）+ 重名重试（D4）。
 */
class FileServiceTest {

    private FileRepository fileRepo;
    private FolderRepository folderRepo;
    private UserRepository userRepo;
    private MinioStorage storage;
    private AgentNotifier notifier;
    private CacheService cache;
    private FileService svc;

    @BeforeEach
    void setUp() {
        fileRepo = mock(FileRepository.class);
        folderRepo = mock(FolderRepository.class);
        userRepo = mock(UserRepository.class);
        storage = mock(MinioStorage.class);
        notifier = mock(AgentNotifier.class);
        cache = mock(CacheService.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        svc = new FileService(fileRepo, folderRepo, userRepo, storage, cache, notifier, txManager);
    }

    private void quotaOk() {
        when(userRepo.tryAddStorageUsed(anyLong(), anyLong())).thenReturn(1);
    }

    private void nameFree() {
        when(fileRepo.nameTaken(anyLong(), any(), any(), anyLong())).thenReturn(false);
    }

    private void nameTakenFor(String... names) {
        when(fileRepo.nameTaken(anyLong(), any(), any(), anyLong())).thenAnswer(inv -> {
            String name = inv.getArgument(2);
            for (String n : names) {
                if (n.equals(name)) {
                    return true;
                }
            }
            return false;
        });
    }

    @Test
    void freeNameReturnedAsIs() {
        nameFree();
        assertThat(svc.uniqueName(1L, null, "hello.txt", 0L)).isEqualTo("hello.txt");
    }

    @Test
    void takenNameGetsNumericSuffix() {
        nameTakenFor("hello.txt");
        assertThat(svc.uniqueName(1L, null, "hello.txt", 0L)).isEqualTo("hello(1).txt");
    }

    @Test
    void consecutiveSuffixes() {
        nameTakenFor("hello.txt", "hello(1).txt");
        assertThat(svc.uniqueName(1L, null, "hello.txt", 0L)).isEqualTo("hello(2).txt");
    }

    @Test
    void existingSuffixIncrements() {
        // hello(1).txt 已存在 → 从 2 起
        nameTakenFor("hello(1).txt");
        assertThat(svc.uniqueName(1L, null, "hello(1).txt", 0L)).isEqualTo("hello(2).txt");
    }

    @Test
    void noExtensionFile() {
        nameTakenFor("readme");
        assertThat(svc.uniqueName(1L, null, "readme", 0L)).isEqualTo("readme(1)");
    }

    @Test
    void excludeSelfDoesNotCount() {
        // excludeID 排除自身：即使同名也存在，视为未占用
        nameFree();
        assertThat(svc.uniqueName(1L, null, "hello.txt", 5L)).isEqualTo("hello.txt");
    }

    @Test
    void renamedKeepsExt() {
        nameTakenFor("a.txt", "a(1).txt", "a(2).txt");
        assertThat(svc.uniqueName(1L, null, "a.txt", 0L)).isEqualTo("a(3).txt");
    }

    // ---------- overwriteContent ----------

    private FileRecord ownedFile(Long id, Long owner, long size) {
        FileRecord f = new FileRecord();
        f.setId(id);
        f.setOwnerId(owner);
        f.setName("a.txt");
        f.setSize(size);
        f.setMd5("oldmd5");
        f.setObjectKey("users/" + owner + "/old-key-a.txt");
        f.setMimeType("text/plain");
        return f;
    }

    private User userWithStorage(long limit) {
        User u = new User();
        u.setId(1L);
        u.setStorageUsed(0L);
        u.setStorageLimit(limit);
        return u;
    }

    @Test
    void overwriteContentWritesNewObjectAndUpdatesRecord() {
        FileRecord f = ownedFile(9L, 1L, 5);
        when(fileRepo.findById(9L)).thenReturn(Optional.of(f));
        when(userRepo.findById(1L)).thenReturn(Optional.of(userWithStorage(1_000_000L)));
        quotaOk();
        when(fileRepo.save(any(FileRecord.class))).thenAnswer(inv -> {
            FileRecord r = inv.getArgument(0);
            r.setId(9L);
            return r;
        });

        FileRecord out = svc.overwriteContent(9L, 1L, "你好，新内容");

        assertThat(out.getSize()).isEqualTo("你好，新内容".getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertThat(out.getMd5()).isNotEqualTo("oldmd5");
        assertThat(out.getObjectKey()).isNotEqualTo("users/1/old-key-a.txt");
        verify(storage).upload(anyString(), any(), anyLong(), anyString());
        verify(storage).delete("users/1/old-key-a.txt");
        verify(notifier).notifyReindex(9L, 1L);
    }

    @Test
    void overwriteContentRejectsNonOwner() {
        FileRecord f = ownedFile(9L, 2L, 5);
        when(fileRepo.findById(9L)).thenReturn(Optional.of(f));

        assertThatThrownBy(() -> svc.overwriteContent(9L, 1L, "x"))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("access denied");
    }

    // ---------- createTextFile ----------

    @Test
    void createTextFileDelegatesToUpload() {
        nameFree();
        quotaOk();
        when(userRepo.findById(1L)).thenReturn(Optional.of(userWithStorage(1_000_000L)));
        when(fileRepo.save(any(FileRecord.class))).thenAnswer(inv -> {
            FileRecord r = inv.getArgument(0);
            r.setId(9L);
            return r;
        });

        FileRecord out = svc.createTextFile(1L, "hello.cc", null, "int main() { return 0; }");

        assertThat(out.getName()).isEqualTo("hello.cc");
        assertThat(out.getMimeType()).isEqualTo("text/plain");
        assertThat(out.getSize()).isEqualTo("int main() { return 0; }".getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        verify(storage).upload(anyString(), any(), anyLong(), anyString());
        // D8：新建即通知建索引
        verify(notifier).notifyReindex(anyLong(), anyLong());
    }

    @Test
    void createTextFileRejectsBlankName() {
        assertThatThrownBy(() -> svc.createTextFile(1L, "  ", null, "x"))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("name required");
    }

    @Test
    void overwriteContentRejectsMissingFile() {
        when(fileRepo.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.overwriteContent(99L, 1L, "x"))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void overwriteContentRejectsStorageExceeded() {
        FileRecord f = ownedFile(9L, 1L, 5);
        when(fileRepo.findById(9L)).thenReturn(Optional.of(f));
        when(userRepo.findById(1L)).thenReturn(Optional.of(userWithStorage(3L))); // 容量不足

        assertThatThrownBy(() -> svc.overwriteContent(9L, 1L, "很长的内容内容内容内容内容内容"))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("storage limit");
    }

    // ---------- readContent ----------

    private FileRecord textFile(Long id, Long owner, String name) {
        FileRecord f = new FileRecord();
        f.setId(id);
        f.setOwnerId(owner);
        f.setName(name);
        f.setSize(10L);
        f.setMimeType("text/plain");
        f.setObjectKey("users/" + owner + "/" + name);
        return f;
    }

    private void mockDownload(String content) throws Exception {
        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(storage.download(anyString())).thenAnswer(inv -> {
            software.amazon.awssdk.http.SdkHttpResponse http =
                    software.amazon.awssdk.http.SdkHttpResponse.builder()
                            .statusCode(200).build();
            return new software.amazon.awssdk.core.ResponseInputStream<>(
                    http, new java.io.ByteArrayInputStream(bytes));
        });
    }

    @Test
    void readContentSlicesByOffsetAndLimit() throws Exception {
        FileRecord f = textFile(10L, 1L, "a.txt");
        when(fileRepo.findById(10L)).thenReturn(Optional.of(f));
        mockDownload("line1\nline2\nline3\nline4\nline5");

        FileService.ContentView view = svc.readContent(10L, 1L, 2, 2);
        assertThat(view.content()).isEqualTo("line2\nline3");
        assertThat(view.totalLines()).isEqualTo(5);
        assertThat(view.truncated()).isTrue();
    }

    @Test
    void readContentFullWhenNoLimit() throws Exception {
        FileRecord f = textFile(10L, 1L, "a.txt");
        when(fileRepo.findById(10L)).thenReturn(Optional.of(f));
        mockDownload("a\nb\nc");

        FileService.ContentView view = svc.readContent(10L, 1L, 1, null);
        assertThat(view.content()).isEqualTo("a\nb\nc");
        assertThat(view.truncated()).isFalse();
    }

    @Test
    void readContentRejectsNonTextName() throws Exception {
        FileRecord f = textFile(10L, 1L, "photo.jpg");
        when(fileRepo.findById(10L)).thenReturn(Optional.of(f));

        assertThatThrownBy(() -> svc.readContent(10L, 1L, 1, null))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("not a text file");
    }

    @Test
    void readContentRejectsNonOwner() throws Exception {
        FileRecord f = textFile(10L, 2L, "a.txt");
        when(fileRepo.findById(10L)).thenReturn(Optional.of(f));

        assertThatThrownBy(() -> svc.readContent(10L, 1L, 1, null))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("access denied");
    }

    @Test
    void readContentOffsetBeyondEndReturnsEmpty() throws Exception {
        FileRecord f = textFile(10L, 1L, "a.txt");
        when(fileRepo.findById(10L)).thenReturn(Optional.of(f));
        mockDownload("a\nb");

        FileService.ContentView view = svc.readContent(10L, 1L, 99, 10);
        assertThat(view.content()).isEqualTo("");
        assertThat(view.totalLines()).isEqualTo(2);
    }

    // ---------- getFileById owner 校验（B1） ----------

    @Test
    void getFileByIdScopedToOwner() {
        FileRecord f = ownedFile(9L, 1L, 5);
        when(fileRepo.findById(9L)).thenReturn(Optional.of(f));

        assertThat(svc.getFileById(9L, 1L)).isSameAs(f);
    }

    @Test
    void getFileByIdRejectsNonOwner() {
        when(fileRepo.findById(9L)).thenReturn(Optional.of(ownedFile(9L, 2L, 5)));

        assertThatThrownBy(() -> svc.getFileById(9L, 1L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("access denied");
    }

    @Test
    void getFileByIdNotFound() {
        when(fileRepo.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.getFileById(99L, 1L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("not found");
    }

    // ---------- 文件夹归属校验（B2） ----------

    private Folder folder(Long id, Long owner) {
        Folder f = new Folder();
        f.setId(id);
        f.setOwnerId(owner);
        return f;
    }

    private void cacheEmpty() {
        when(cache.get(anyString(), any())).thenReturn(Optional.empty());
    }

    @Test
    void uploadRejectsForeignFolder() {
        when(userRepo.findById(1L)).thenReturn(Optional.of(userWithStorage(1_000_000L)));
        when(folderRepo.findById(5L)).thenReturn(Optional.of(folder(5L, 2L)));

        assertThatThrownBy(() -> svc.upload(1L, new byte[1], "a.txt", 5L, "text/plain"))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("access denied");
        verify(storage, never()).upload(anyString(), any(), anyLong(), anyString());
    }

    @Test
    void uploadRejectsMissingFolder() {
        when(userRepo.findById(1L)).thenReturn(Optional.of(userWithStorage(1_000_000L)));
        when(folderRepo.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.upload(1L, new byte[1], "a.txt", 5L, "text/plain"))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("target folder not found");
    }

    @Test
    void uploadAcceptsOwnFolder() {
        nameFree();
        quotaOk();
        when(userRepo.findById(1L)).thenReturn(Optional.of(userWithStorage(1_000_000L)));
        when(folderRepo.findById(5L)).thenReturn(Optional.of(folder(5L, 1L)));
        when(fileRepo.save(any(FileRecord.class))).thenAnswer(inv -> {
            FileRecord r = inv.getArgument(0);
            r.setId(9L);
            return r;
        });

        FileRecord out = svc.upload(1L, new byte[3], "a.txt", 5L, "text/plain");

        assertThat(out.getFolderId()).isEqualTo(5L);
        verify(storage).upload(anyString(), any(), anyLong(), anyString());
    }

    @Test
    void uploadNormalizesRootFolderToNull() {
        nameFree();
        quotaOk();
        when(userRepo.findById(1L)).thenReturn(Optional.of(userWithStorage(1_000_000L)));
        when(fileRepo.save(any(FileRecord.class))).thenAnswer(inv -> {
            FileRecord r = inv.getArgument(0);
            r.setId(9L);
            return r;
        });

        FileRecord zero = svc.upload(1L, new byte[3], "a.txt", 0L, "text/plain");
        FileRecord none = svc.upload(1L, new byte[3], "b.txt", null, "text/plain");

        assertThat(zero.getFolderId()).isNull();
        assertThat(none.getFolderId()).isNull();
        verify(folderRepo, never()).findById(anyLong());
    }

    @Test
    void checksumInstantRejectsForeignFolder() {
        cacheEmpty();
        when(fileRepo.findFirstByMd5AndOwnerId("abc", 1L)).thenReturn(Optional.of(ownedFile(9L, 1L, 10)));
        when(userRepo.findById(1L)).thenReturn(Optional.of(userWithStorage(1_000_000L)));
        when(folderRepo.findById(5L)).thenReturn(Optional.of(folder(5L, 2L)));

        assertThatThrownBy(() -> svc.checksumInstant(1L, "abc", "a.txt", 10L, 5L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("access denied");
        verify(storage, never()).copyObject(anyString(), anyString());
    }

    @Test
    void listByFolderScopesToOwner() {
        svc.listByFolder(5L, 1L);

        verify(fileRepo).findByFolderIdAndOwnerIdOrderByCreatedAtDesc(5L, 1L);
        verify(fileRepo, never()).findByFolderIdOrderByCreatedAtDesc(anyLong());
    }

    // ---------- 配额原子预占（D1） ----------

    @Test
    void uploadRejectsWhenAtomicReserveFails() {
        // 乐观预检通过（容量充足），但并发下原子预占返回 0 → 拒绝并清理已写对象
        when(userRepo.findById(1L)).thenReturn(Optional.of(userWithStorage(1_000_000L)));
        when(userRepo.tryAddStorageUsed(anyLong(), anyLong())).thenReturn(0);
        nameFree();
        when(fileRepo.save(any(FileRecord.class))).thenAnswer(inv -> {
            FileRecord r = inv.getArgument(0);
            r.setId(9L);
            return r;
        });

        assertThatThrownBy(() -> svc.upload(1L, new byte[200], "a.txt", null, "text/plain"))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("storage limit");
        verify(storage).delete(anyString());
        verify(userRepo).tryAddStorageUsed(1L, 200L);
    }

    // ---------- overwriteContent 扣旧大小（D2） ----------

    @Test
    void overwriteContentDeductsOldSize() {
        // used=100 limit=110 oldSize=50：新内容 21B 时 100-50+21=71 <= 110 应放行（不扣旧大小则 121>110 拒绝）
        FileRecord f = ownedFile(9L, 1L, 50);
        when(fileRepo.findById(9L)).thenReturn(Optional.of(f));
        User u = new User();
        u.setId(1L);
        u.setStorageUsed(100L);
        u.setStorageLimit(110L);
        when(userRepo.findById(1L)).thenReturn(Optional.of(u));
        quotaOk();
        when(fileRepo.save(any(FileRecord.class))).thenAnswer(inv -> {
            FileRecord r = inv.getArgument(0);
            r.setId(9L);
            return r;
        });

        FileRecord out = svc.overwriteContent(9L, 1L, "x".repeat(21));

        assertThat(out.getSize()).isEqualTo(21L);
        verify(userRepo).tryAddStorageUsed(1L, 21L - 50L);
    }

    // ---------- 并发重名换名重试（D4） ----------

    @Test
    void uploadRetriesOnceOnNameConflict() {
        when(userRepo.findById(1L)).thenReturn(Optional.of(userWithStorage(1_000_000L)));
        quotaOk();
        when(fileRepo.save(any(FileRecord.class))).thenAnswer(inv -> {
            FileRecord r = inv.getArgument(0);
            r.setId(9L);
            if (saveCalls.incrementAndGet() == 1) {
                throw new DataIntegrityViolationException("duplicate name");
            }
            return r;
        });
        // nameTaken：首轮 a.txt 未占用 → 重试轮 a.txt 已被并发者占用（→ a(1).txt），a(1).txt 未占用
        when(fileRepo.nameTaken(anyLong(), any(), anyString(), anyLong())).thenAnswer(inv -> {
            int n = ntCalls.incrementAndGet();
            if (n == 2 && "a.txt".equals(inv.getArgument(2))) {
                return true;
            }
            return false;
        });

        FileRecord out = svc.upload(1L, new byte[3], "a.txt", null, "text/plain");

        assertThat(out.getName()).isEqualTo("a(1).txt");
        verify(fileRepo, times(2)).save(any(FileRecord.class));
        verify(userRepo, times(2)).tryAddStorageUsed(anyLong(), anyLong());
        verify(notifier).notifyReindex(anyLong(), anyLong());
    }

    // ---------- 秒传通知建索引（D8） ----------

    @Test
    void checksumInstantNotifiesReindex() {
        cacheEmpty();
        when(fileRepo.findFirstByMd5AndOwnerId("abc", 1L)).thenReturn(Optional.of(ownedFile(9L, 1L, 10)));
        when(userRepo.findById(1L)).thenReturn(Optional.of(userWithStorage(1_000_000L)));
        quotaOk();
        when(fileRepo.save(any(FileRecord.class))).thenAnswer(inv -> {
            FileRecord r = inv.getArgument(0);
            r.setId(9L);
            return r;
        });

        FileRecord out = svc.checksumInstant(1L, "abc", "b.txt", 10L, null);

        assertThat(out).isNotNull();
        verify(notifier).notifyReindex(9L, 1L);
    }

    private final AtomicInteger saveCalls = new AtomicInteger();
    private final AtomicInteger ntCalls = new AtomicInteger();
}
