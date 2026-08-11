package com.clouddrive.dto;

import java.time.format.DateTimeFormatter;

import com.clouddrive.entity.FileRecord;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import lombok.Data;

/**
 * 文件信息 DTO，JSON 字段对齐 Go types.FileInfo（snake_case；folder_id 根目录时为 null）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class FileInfo {

    private Long id;
    private String name;
    private Long size;
    private String mimeType;
    private String md5;
    private Long folderId;
    private String createdAt;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    public static FileInfo from(FileRecord f) {
        FileInfo dto = new FileInfo();
        dto.setId(f.getId());
        dto.setName(f.getName());
        dto.setSize(f.getSize());
        dto.setMimeType(f.getMimeType());
        dto.setMd5(f.getMd5());
        dto.setFolderId(f.getFolderId());
        dto.setCreatedAt(f.getCreatedAt() == null ? "" : f.getCreatedAt().format(FMT));
        return dto;
    }
}
