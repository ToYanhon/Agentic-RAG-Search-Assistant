package com.clouddrive.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.clouddrive.common.Resp;
import com.clouddrive.dto.FileInfo;
import com.clouddrive.entity.Share;
import com.clouddrive.service.FileService;
import com.clouddrive.service.ShareService;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 分享接口（对齐 Go share_handler）：
 * 创建/删除在 /api/v1/shares（JWT）；公开访问在 /s/:token 与 /s/:token/download（无鉴权）。
 */
@RestController
public class ShareController {

    private final ShareService shareService;
    private final FileService fileService;

    public ShareController(ShareService shareService, FileService fileService) {
        this.shareService = shareService;
        this.fileService = fileService;
    }

    @Data
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class CreateReq {
        @NotNull
        private Long fileId;
        private Integer expireHours;
    }

    @PostMapping("/api/v1/shares")
    @ResponseStatus(HttpStatus.CREATED)
    public Resp<Map<String, Object>> create(@Valid @RequestBody CreateReq req, HttpServletRequest request) {
        Share share = shareService.create(userId(request), req.getFileId(), req.getExpireHours());
        Map<String, Object> data = new HashMap<>();
        data.put("id", share.getId());
        data.put("token", share.getToken());
        data.put("url", "/s/" + share.getToken());
        return Resp.created(data);
    }

    @DeleteMapping("/api/v1/shares/{id}")
    public Resp<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        shareService.delete(id, userId(request));
        return Resp.ok(null);
    }

    @GetMapping("/s/{token}")
    public Resp<FileInfo> access(@PathVariable String token) {
        Share share = shareService.getShare(token);
        return Resp.ok(FileInfo.from(fileService.getFileById(share.getFileId())));
    }

    @GetMapping("/s/{token}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable String token) {
        Share share = shareService.getShare(token);
        FileService.DownloadResult r = fileService.downloadById(share.getFileId());
        String mime = r.file().getMimeType();
        if (mime == null || mime.isEmpty()) {
            mime = "application/octet-stream";
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(mime));
        headers.add("Content-Disposition", "attachment; filename=" + r.file().getName());
        headers.setContentLength(r.file().getSize());
        return new ResponseEntity<>(new InputStreamResource(r.stream()), headers, HttpStatus.OK);
    }

    private Long userId(HttpServletRequest request) {
        return (Long) request.getAttribute("user_id");
    }
}
