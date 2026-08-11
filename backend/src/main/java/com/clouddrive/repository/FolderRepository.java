package com.clouddrive.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.clouddrive.entity.Folder;

/**
 * 文件夹数据访问（对齐 Go folderRepo）。
 */
public interface FolderRepository extends JpaRepository<Folder, Long> {

    List<Folder> findByOwnerIdAndParentId(Long ownerId, Long parentId);

    List<Folder> findByOwnerIdAndParentIdIsNull(Long ownerId);

    @Modifying
    @Query("update Folder f set f.name = :name where f.id = :id")
    void updateName(@Param("id") Long id, @Param("name") String name);

    @Modifying
    @Query("update Folder f set f.parentId = :parentId where f.id = :id")
    void updateParent(@Param("id") Long id, @Param("parentId") Long parentId);

    /** 递归收集 id 及其全部子文件夹 id（对齐 Go CollectChildIDs，MySQL 8 CTE）。 */
    @Query(value = """
            WITH RECURSIVE cte AS (
                SELECT id FROM folders WHERE id = :id
                UNION ALL
                SELECT f.id FROM folders f JOIN cte ON f.parent_id = cte.id
            )
            SELECT id FROM cte
            """, nativeQuery = true)
    List<Long> collectChildIds(@Param("id") Long id);
}
