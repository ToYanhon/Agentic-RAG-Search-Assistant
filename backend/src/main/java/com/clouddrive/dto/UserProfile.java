package com.clouddrive.dto;

import java.time.format.DateTimeFormatter;

import com.clouddrive.entity.User;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import lombok.Data;

/**
 * 用户资料 DTO，JSON 字段对齐 Go types.UserProfile（snake_case）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class UserProfile {

    private Long id;
    private String username;
    private String email;
    private Long storageUsed;
    private Long storageLimit;
    /** 与 Go 一致：本地墙钟 + 字面 Z 后缀（yyyy-MM-dd'T'HH:mm:ss'Z'）。 */
    private String createdAt;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    public static UserProfile from(User u) {
        UserProfile p = new UserProfile();
        p.setId(u.getId());
        p.setUsername(u.getUsername());
        p.setEmail(u.getEmail());
        p.setStorageUsed(u.getStorageUsed());
        p.setStorageLimit(u.getStorageLimit());
        p.setCreatedAt(u.getCreatedAt() == null ? "" : u.getCreatedAt().format(FMT));
        return p;
    }
}
