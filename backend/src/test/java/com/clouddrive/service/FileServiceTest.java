package com.clouddrive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.clouddrive.repository.FileRepository;

/**
 * FileService.uniqueName 去重逻辑测试（同文件夹内同名自动追加序号）。
 */
class FileServiceTest {

    private FileRepository fileRepo;
    private FileService svc;

    @BeforeEach
    void setUp() {
        fileRepo = mock(FileRepository.class);
        svc = new FileService(fileRepo, null, null, null, null, null);
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
}
