package com.clouddrive.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
import com.clouddrive.service.MultipartService;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 分块上传接口（对齐 Go multipart_handler）：init / parts / complete / abort。
 */
@RestController
@RequestMapping("/api/v1/files/multipart")
public class MultipartController {

    private static final long ONE_MB = 1024 * 1024;
    private static final long DEFAULT_CHUNK_SIZE = 5 * ONE_MB;

    private final MultipartService multipartService;
    private final AppProperties props;

    public MultipartController(MultipartService multipartService, AppProperties props) {
        this.multipartService = multipartService;
        this.props = props;
    }

    @Data
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class InitReq {
        @NotBlank
        private String name;
        @NotNull
        private Long size;
        private String mimeType;
        private Long folderId;
        private String md5;
        @Min(0)
        private Long chunkSize;
    }

    private Long userId(HttpServletRequest request) {
        return (Long) request.getAttribute("user_id");
    }

    @PostMapping("/init")
    @ResponseStatus(HttpStatus.CREATED)
    public Resp<Map<String, Object>> init(@Valid @RequestBody InitReq req, HttpServletRequest request) {
        long fileMax = props.getUpload().getFileMaxBytes();
        if (req.getSize() <= 0 || req.getSize() > fileMax) {
            throw AppException.fileTooLarge("file size exceeds upload limit");
        }
        long chunkMax = props.getUpload().getChunkMaxBytes();
        long chunkSize = req.getChunkSize() == null || req.getChunkSize() <= 0 ? DEFAULT_CHUNK_SIZE : req.getChunkSize();
        if (chunkSize < ONE_MB || chunkSize > chunkMax) {
            throw AppException.badRequest("chunk_size out of range (1MB ~ 10MB)");
        }
        MultipartService.MultipartMeta meta = multipartService.init(userId(request), req.getName(),
                req.getMimeType(), req.getSize(), req.getFolderId(), req.getMd5(), chunkSize);
        Map<String, Object> data = new HashMap<>();
        data.put("upload_id", meta.getUploadId());
        data.put("chunk_size", meta.getChunkSize());
        data.put("total_chunks", meta.getTotalChunks());
        data.put("remaining", meta.getRemaining()); // D3：剩余可用配额（字节）
        return Resp.created(data);
    }

    @PostMapping("/{upload_id}/parts")
    public Resp<Map<String, Object>> uploadPart(@PathVariable("upload_id") String uploadId,
                                                @RequestParam(value = "index", defaultValue = "0") int index,
                                                @RequestParam(value = "data", required = false) MultipartFile data,
                                                HttpServletRequest request) {
        if (data == null || data.isEmpty()) {
            throw AppException.badRequest("data part required");
        }
        if (index < 0) {
            throw AppException.badRequest("invalid index");
        }
        long chunkMax = props.getUpload().getChunkMaxBytes();
        if (data.getSize() > chunkMax) {
            throw AppException.fileTooLarge("part exceeds chunk size limit");
        }
        byte[] bytes;
        try {
            bytes = data.getBytes();
        } catch (Exception e) {
            throw AppException.internal("read part failed");
        }
        List<Integer> received = multipartService.uploadPart(uploadId, userId(request), index, bytes);
        return Resp.ok(Map.of("received", received));
    }

    @PostMapping("/{upload_id}/complete")
    public Resp<FileInfo> complete(@PathVariable("upload_id") String uploadId, HttpServletRequest request) {
        FileRecord f = multipartService.complete(uploadId, userId(request));
        return Resp.ok(FileInfo.from(f));
    }

    @DeleteMapping("/{upload_id}")
    public Resp<Void> abort(@PathVariable("upload_id") String uploadId, HttpServletRequest request) {
        multipartService.abort(uploadId, userId(request));
        return Resp.ok(null);
    }
}
