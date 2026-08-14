package com.clouddrive.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.clouddrive.entity.Folder;
import com.clouddrive.repository.FolderRepository;

/** FolderService 级联删除测试（D7）：删除根与全部子文件夹行，不再留孤儿记录。 */
class FolderServiceTest {

    private FolderRepository folderRepo;
    private FileService fileService;
    private FolderService svc;

    @BeforeEach
    void setUp() {
        folderRepo = mock(FolderRepository.class);
        fileService = mock(FileService.class);
        svc = new FolderService(folderRepo, fileService);
    }

    private Folder folder(Long id, Long owner) {
        Folder f = new Folder();
        f.setId(id);
        f.setOwnerId(owner);
        return f;
    }

    @Test
    void deleteRemovesRootAndAllChildFolders() {
        when(folderRepo.findById(1L)).thenReturn(Optional.of(folder(1L, 7L)));
        when(folderRepo.collectChildIds(1L)).thenReturn(List.of(1L, 2L, 3L));

        svc.delete(1L, 7L);

        // 文件级联删除覆盖全部子文件夹；文件夹行批量删除根 + 子树（collectChildIds 已含根 id）
        verify(fileService).deleteByFolderIds(List.of(1L, 2L, 3L));
        verify(folderRepo).deleteAllByIdInBatch(List.of(1L, 2L, 3L));
        verify(folderRepo, never()).deleteById(anyLong());
    }

    @Test
    void deleteRejectsForeignOwner() {
        when(folderRepo.findById(1L)).thenReturn(Optional.of(folder(1L, 8L)));

        assertThatThrownBy(() -> svc.delete(1L, 7L))
                .hasMessageContaining("access denied");
        verify(folderRepo, never()).deleteAllByIdInBatch(any());
        verify(fileService, never()).deleteByFolderIds(any());
    }
}
