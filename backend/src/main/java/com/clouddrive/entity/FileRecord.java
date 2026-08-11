package com.clouddrive.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** 文件表 files。folder_id 为空表示位于根目录。 */
@Entity
@Getter
@Setter
@Table(name = "files", indexes = {
        @Index(name = "idx_files_md5", columnList = "md5"),
        @Index(name = "idx_files_owner", columnList = "owner_id"),
        @Index(name = "idx_files_folder", columnList = "folder_id"),
})
public class FileRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false)
    private Long size;

    @Column(name = "mime_type", length = 128)
    private String mimeType;

    @Column(length = 32)
    private String md5;

    @Column(name = "object_key", nullable = false, length = 512)
    private String objectKey;

    @Column(name = "folder_id")
    private Long folderId;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
