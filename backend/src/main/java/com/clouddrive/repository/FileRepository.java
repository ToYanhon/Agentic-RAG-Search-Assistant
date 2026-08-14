package com.clouddrive.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.clouddrive.entity.FileRecord;

/**
 * 文件数据访问（对齐 Go fileRepo）。
 */
public interface FileRepository extends JpaRepository<FileRecord, Long> {

    List<FileRecord> findByFolderIdOrderByCreatedAtDesc(Long folderId);

    List<FileRecord> findByFolderIdAndOwnerIdOrderByCreatedAtDesc(Long folderId, Long ownerId);

    List<FileRecord> findByOwnerIdOrderByCreatedAtDesc(Long ownerId);

    Optional<FileRecord> findFirstByMd5AndOwnerId(String md5, Long ownerId);

    List<FileRecord> findByFolderIdIn(Collection<Long> folderIds);

    long countByOwnerId(Long ownerId);

    List<FileRecord> findByOwnerIdOrderByCreatedAtDesc(Long ownerId, Pageable pageable);

    long countByOwnerIdAndNameContaining(Long ownerId, String name);

    List<FileRecord> findByOwnerIdAndNameContainingOrderByCreatedAtDesc(Long ownerId, String name, Pageable pageable);

    @Modifying
    @Query("update FileRecord f set f.name = :name where f.id = :id")
    void updateName(@Param("id") Long id, @Param("name") String name);

    @Modifying
    @Query("update FileRecord f set f.folderId = :folderId where f.id = :id")
    void updateFolderId(@Param("id") Long id, @Param("folderId") Long folderId);

    /**
     * 同 owner 同文件夹（folderId 为 null 表示根目录）下是否存在同名文件，可排除自身（excludeId<=0 不排除）。
     * 对齐 Go NameTaken。
     */
    @Query("""
            select count(f) > 0 from FileRecord f
            where f.ownerId = :owner and f.name = :name
              and ((:folderId is null and f.folderId is null) or (:folderId is not null and f.folderId = :folderId))
              and (:excludeId is null or :excludeId <= 0 or f.id <> :excludeId)
            """)
    boolean nameTaken(@Param("owner") Long owner, @Param("folderId") Long folderId,
                      @Param("name") String name, @Param("excludeId") Long excludeId);
}
