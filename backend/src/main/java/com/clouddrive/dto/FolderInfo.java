package com.clouddrive.dto;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.clouddrive.entity.Folder;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import lombok.Data;

/**
 * 文件夹信息 DTO，对齐 Go types.FolderInfo（children/files 恒为数组）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class FolderInfo {

    private Long id;
    private String name;
    private Long parentId;
    private String createdAt;
    private List<FolderInfo> children = new ArrayList<>();
    private List<FileInfo> files = new ArrayList<>();

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    public static FolderInfo from(Folder f) {
        FolderInfo dto = new FolderInfo();
        dto.setId(f.getId());
        dto.setName(f.getName());
        dto.setParentId(f.getParentId());
        dto.setCreatedAt(f.getCreatedAt() == null ? "" : f.getCreatedAt().format(FMT));
        return dto;
    }
}
