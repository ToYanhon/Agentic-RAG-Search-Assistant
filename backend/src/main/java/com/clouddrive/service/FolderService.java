package com.clouddrive.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.clouddrive.common.AppException;
import com.clouddrive.dto.FileInfo;
import com.clouddrive.dto.FolderInfo;
import com.clouddrive.entity.FileRecord;
import com.clouddrive.entity.Folder;
import com.clouddrive.repository.FolderRepository;

/**
 * 文件夹业务（对齐 Go folderService）：创建/树/根/重命名/移动(环检测)/级联删除。
 */
@Service
public class FolderService {

    private final FolderRepository folderRepo;
    private final FileService fileService;

    public FolderService(FolderRepository folderRepo, FileService fileService) {
        this.folderRepo = folderRepo;
        this.fileService = fileService;
    }

    @Transactional
    public Folder create(String name, Long parentId, Long ownerId) {
        Folder f = new Folder();
        f.setName(name);
        f.setParentId(parentId);
        f.setOwnerId(ownerId);
        return folderRepo.save(f);
    }

    /** 文件夹树：本文件夹 + 直接子文件夹（无嵌套）+ 直接文件（对齐 Go GetTree）。 */
    public FolderInfo getTree(Long folderId, Long ownerId) {
        Folder f = folderRepo.findById(folderId)
                .orElseThrow(() -> AppException.notFound("resource not found"));
        if (!f.getOwnerId().equals(ownerId)) {
            throw AppException.forbidden("access denied");
        }
        FolderInfo info = FolderInfo.from(f);
        for (Folder child : folderRepo.findByOwnerIdAndParentId(ownerId, folderId)) {
            info.getChildren().add(FolderInfo.from(child));
        }
        for (FileRecord file : fileService.listByFolder(folderId, ownerId)) {
            info.getFiles().add(FileInfo.from(file));
        }
        return info;
    }

    /** 根文件夹列表（每个含直属文件，对齐 Go GetRoot）。 */
    public List<FolderInfo> getRoot(Long ownerId) {
        List<FolderInfo> out = new ArrayList<>();
        for (Folder f : folderRepo.findByOwnerIdAndParentIdIsNull(ownerId)) {
            FolderInfo info = FolderInfo.from(f);
            for (FileRecord file : fileService.listByFolder(f.getId(), ownerId)) {
                info.getFiles().add(FileInfo.from(file));
            }
            out.add(info);
        }
        return out;
    }

    @Transactional
    public void rename(Long id, String name, Long ownerId) {
        Folder f = folderRepo.findById(id)
                .orElseThrow(() -> AppException.notFound("resource not found"));
        if (!f.getOwnerId().equals(ownerId)) {
            throw AppException.forbidden("access denied");
        }
        folderRepo.updateName(id, name);
    }

    @Transactional
    public void move(Long id, long targetParentId, Long ownerId) {
        Folder f = folderRepo.findById(id)
                .orElseThrow(() -> AppException.notFound("resource not found"));
        if (!f.getOwnerId().equals(ownerId)) {
            throw AppException.forbidden("access denied");
        }
        if (targetParentId == 0) {
            folderRepo.updateParent(id, null);
            return;
        }
        if (id == targetParentId) {
            throw AppException.folderCycle("cannot move folder into its own descendant");
        }
        Folder parent = folderRepo.findById(targetParentId)
                .orElseThrow(() -> AppException.badRequest("target folder not found"));
        if (!parent.getOwnerId().equals(ownerId)) {
            throw AppException.forbidden("access denied");
        }
        if (folderRepo.collectChildIds(id).contains(targetParentId)) {
            throw AppException.folderCycle("cannot move folder into its own descendant");
        }
        folderRepo.updateParent(id, targetParentId);
    }

    /** 级联删除：删本文件夹及其子树内全部文件（含 Agent unindex），并删根文件夹记录。 */
    @Transactional
    public void delete(Long id, Long ownerId) {
        Folder f = folderRepo.findById(id)
                .orElseThrow(() -> AppException.notFound("resource not found"));
        if (!f.getOwnerId().equals(ownerId)) {
            throw AppException.forbidden("access denied");
        }
        List<Long> childIds = folderRepo.collectChildIds(id);
        fileService.deleteByFolderIds(childIds);
        folderRepo.deleteById(id);
    }
}
