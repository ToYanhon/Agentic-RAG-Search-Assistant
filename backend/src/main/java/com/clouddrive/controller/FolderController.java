package com.clouddrive.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.clouddrive.common.Resp;
import com.clouddrive.dto.FolderInfo;
import com.clouddrive.entity.Folder;
import com.clouddrive.service.FolderService;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 文件夹接口（对齐 Go folder_handler）。
 */
@RestController
@RequestMapping("/api/v1/folders")
public class FolderController {

    private final FolderService folderService;

    public FolderController(FolderService folderService) {
        this.folderService = folderService;
    }

    @Data
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class CreateReq {
        @NotBlank
        private String name;
        private Long parentId;
    }

    @Data
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class RenameReq {
        @NotBlank
        private String name;
    }

    @Data
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class MoveReq {
        private Long targetParentId;
    }

    private Long userId(HttpServletRequest request) {
        return (Long) request.getAttribute("user_id");
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Resp<FolderInfo> create(@Valid @RequestBody CreateReq req, HttpServletRequest request) {
        Folder f = folderService.create(req.getName(), req.getParentId(), userId(request));
        return Resp.created(FolderInfo.from(f));
    }

    @GetMapping("/root")
    public Resp<List<FolderInfo>> getRoot(HttpServletRequest request) {
        return Resp.ok(folderService.getRoot(userId(request)));
    }

    @GetMapping("/{id}")
    public Resp<FolderInfo> getTree(@PathVariable Long id, HttpServletRequest request) {
        return Resp.ok(folderService.getTree(id, userId(request)));
    }

    @PutMapping("/{id}")
    public Resp<Void> rename(@PathVariable Long id, @Valid @RequestBody RenameReq req,
                             HttpServletRequest request) {
        folderService.rename(id, req.getName(), userId(request));
        return Resp.ok(null);
    }

    @PutMapping("/{id}/move")
    public Resp<Void> move(@PathVariable Long id, @Valid @RequestBody MoveReq req,
                           HttpServletRequest request) {
        long target = req.getTargetParentId() == null ? 0 : req.getTargetParentId();
        folderService.move(id, target, userId(request));
        return Resp.ok(null);
    }

    @DeleteMapping("/{id}")
    public Resp<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        folderService.delete(id, userId(request));
        return Resp.ok(null);
    }
}
