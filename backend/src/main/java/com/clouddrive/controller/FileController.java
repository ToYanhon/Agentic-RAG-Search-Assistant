package com.clouddrive.controller;

import java.util.HashMap;
import java.util.List;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.clouddrive.common.AppException;
import com.clouddrive.common.Resp;
import com.clouddrive.config.AppProperties;
import com.clouddrive.dto.FileInfo;
import com.clouddrive.entity.FileRecord;
import com.clouddrive.service.FileService;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 文件接口（对齐 Go file_handler）：上传/下载/删除/秒传/重命名/移动/搜索/列表。
 */
@RestController
@RequestMapping("/api/v1/files")
public class FileController {

    private final FileService fileService;
    private final AppProperties props;

    public FileController(FileService fileService, AppProperties props) {
        this.fileService = fileService;
        this.props = props;
    }

    @Data
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class ChecksumReq {
        @NotBlank
        private String md5;
        @NotBlank
        private String name;
        @NotNull
        private Long size;
        private Long folderId;
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
        private Long targetFolderId;
    }

    @Data
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class ContentReq {
        @NotBlank
        private String content;
    }

    @Data
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class CreateTextReq {
        @NotBlank
        private String name;
        @NotBlank
        private String content;
        private Long folderId;
    }

    private Long userId(HttpServletRequest request) {
        return (Long) request.getAttribute("user_id");
    }

    @PostMapping("/upload")
    @ResponseStatus(HttpStatus.CREATED)
    public Resp<FileInfo> upload(@RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "folder_id", required = false) Long folderId,
            HttpServletRequest request) {
        if (file == null || file.isEmpty()) {
            throw AppException.badRequest("file is required");
        }
        if (file.getSize() > props.getUpload().getDirectMaxBytes()) {
            throw AppException.fileTooLarge("file too large, use multipart upload for files over 50MB");
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (Exception e) {
            throw AppException.internal("read upload failed");
        }
        FileRecord f = fileService.upload(userId(request), data, file.getOriginalFilename(), folderId,
                file.getContentType());
        return Resp.created(FileInfo.from(f));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable Long id, HttpServletRequest request) {
        FileService.DownloadResult r = fileService.download(id, userId(request));
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

    @DeleteMapping("/{id}")
    public Resp<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        fileService.delete(id, userId(request));
        return Resp.ok(null);
    }

    @GetMapping("/{id}")
    public Resp<FileInfo> get(@PathVariable Long id, HttpServletRequest request) {
        return Resp.ok(FileInfo.from(fileService.getFileById(id, userId(request))));
    }

    @PostMapping("/checksum")
    public Resp<Map<String, Object>> checksum(@Valid @RequestBody ChecksumReq req, HttpServletRequest request) {
        FileRecord f = fileService.checksumInstant(userId(request), req.getMd5(), req.getName(),
                req.getSize(), req.getFolderId());
        if (f == null) {
            return Resp.ok(Map.of("instant", false));
        }
        return Resp.ok(Map.of("instant", true, "file", FileInfo.from(f)));
    }

    @PutMapping("/{id}")
    public Resp<Void> rename(@PathVariable Long id, @Valid @RequestBody RenameReq req, HttpServletRequest request) {
        fileService.rename(id, userId(request), req.getName());
        return Resp.ok(null);
    }

    @PutMapping("/{id}/move")
    public Resp<Void> move(@PathVariable Long id, @Valid @RequestBody MoveReq req, HttpServletRequest request) {
        long target = req.getTargetFolderId() == null ? 0 : req.getTargetFolderId();
        fileService.move(id, userId(request), target);
        return Resp.ok(null);
    }

    @PutMapping("/{id}/content")
    public Resp<FileInfo> overwriteContent(@PathVariable Long id, @Valid @RequestBody ContentReq req,
            HttpServletRequest request) {
        requireAgent(request);
        FileRecord f = fileService.overwriteContent(id, userId(request), req.getContent());
        return Resp.ok(FileInfo.from(f));
    }

    @GetMapping("/{id}/content")
    public Resp<Map<String, Object>> readContent(@PathVariable Long id,
            @RequestParam(value = "offset", required = false, defaultValue = "1") int offset,
            @RequestParam(value = "limit", required = false) Integer limit,
            HttpServletRequest request) {
        FileService.ContentView view = fileService.readContent(id, userId(request), offset, limit);
        Map<String, Object> data = new HashMap<>();
        data.put("content", view.content());
        data.put("total_lines", view.totalLines());
        data.put("truncated", view.truncated());
        return Resp.ok(data);
    }

    @PostMapping("/text")
    @ResponseStatus(HttpStatus.CREATED)
    public Resp<FileInfo> createTextFile(@Valid @RequestBody CreateTextReq req, HttpServletRequest request) {
        requireAgent(request);
        if (req.getContent().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > props.getUpload().getDirectMaxBytes()) {
            throw AppException.fileTooLarge("content too large");
        }
        FileRecord f = fileService.createTextFile(userId(request), req.getName(), req.getFolderId(), req.getContent());
        return Resp.created(FileInfo.from(f));
    }

    /** 仅 agent 内部调用（X-Agent-Token）可写内容；用户前端不可直接覆盖。 */
    private void requireAgent(HttpServletRequest request) {
        if (!"agent".equals(request.getAttribute("caller"))) {
            throw AppException.forbidden("agent only");
        }
    }

    @GetMapping("/search")
    public Resp<Map<String, Object>> search(@RequestParam(value = "q", required = false) String q,
            HttpServletRequest request) {
        if (q == null || q.isEmpty()) {
            throw AppException.badRequest("query required");
        }
        int[] pp = Pagination.parse(request, 20);
        List<FileInfo> files = fileService.search(userId(request), q, pp[0], pp[1])
                .stream().map(FileInfo::from).toList();
        Map<String, Object> data = new HashMap<>();
        data.put("files", files);
        data.put("total", fileService.countSearch(userId(request), q));
        data.put("page", pp[0]);
        return Resp.ok(data);
    }

    @GetMapping
    public Resp<Map<String, Object>> list(HttpServletRequest request) {
        int[] pp = Pagination.parse(request, 20);
        List<FileInfo> files = fileService.listByOwner(userId(request), pp[0], pp[1])
                .stream().map(FileInfo::from).toList();
        Map<String, Object> data = new HashMap<>();
        data.put("files", files);
        data.put("total", fileService.countOwner(userId(request)));
        data.put("page", pp[0]);
        return Resp.ok(data);
    }
}
