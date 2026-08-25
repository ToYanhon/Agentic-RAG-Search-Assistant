package com.clouddrive.repository;

import com.clouddrive.repository.entity.FolderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 目录查询原生 SQL，对应 Go db.CatalogQuery。
 */
public interface CatalogJpa extends JpaRepository<FolderEntity, Long> {

	interface FileRow {

		Long getId();

		String getName();

		long getSize();

		String getMimeType();

		String getMd5();

		Long getFolderId();

		Long getOwnerId();

		LocalDateTime getCreatedAt();

	}

	interface FolderRow {

		Long getId();

		String getName();

		Long getParentId();

		Long getOwnerId();

		LocalDateTime getCreatedAt();

	}

	@Query(value = "SELECT id, name, size, mime_type AS mimeType, md5, folder_id AS folderId, owner_id AS ownerId, created_at AS createdAt FROM files WHERE id = ?1",
			nativeQuery = true)
	Optional<FileRow> findFile(long id);

	@Query(value = "SELECT id, name, size, mime_type AS mimeType, md5, folder_id AS folderId, owner_id AS ownerId, created_at AS createdAt FROM files WHERE owner_id = ?1 ORDER BY created_at DESC LIMIT ?2, ?3",
			nativeQuery = true)
	List<FileRow> listFiles(long ownerId, int offset, int pageSize);

	@Query(value = "SELECT COUNT(*) FROM files WHERE owner_id = ?1", nativeQuery = true)
	long countFiles(long ownerId);

	@Query(value = "SELECT id, name, size, mime_type AS mimeType, md5, folder_id AS folderId, owner_id AS ownerId, created_at AS createdAt FROM files WHERE owner_id = ?1 AND name LIKE ?2 ORDER BY created_at DESC LIMIT ?3, ?4",
			nativeQuery = true)
	List<FileRow> searchFiles(long ownerId, String pattern, int offset, int pageSize);

	@Query(value = "SELECT COUNT(*) FROM files WHERE owner_id = ?1 AND name LIKE ?2", nativeQuery = true)
	long countSearch(long ownerId, String pattern);

	@Query(value = "SELECT id, name, size, mime_type AS mimeType, md5, folder_id AS folderId, owner_id AS ownerId, created_at AS createdAt FROM files WHERE folder_id = ?1 AND owner_id = ?2 ORDER BY created_at DESC LIMIT ?3, ?4",
			nativeQuery = true)
	List<FileRow> listFolderFiles(long folderId, long ownerId, int offset, int pageSize);

	@Query(value = "SELECT COUNT(*) FROM files WHERE owner_id = ?1 AND name = ?2 AND ((?3 IS NULL AND folder_id IS NULL) OR folder_id = ?3) AND id <> ?4",
			nativeQuery = true)
	int countNameTaken(long ownerId, String name, Long folderId, long excludeId);

	@Modifying
	@Transactional
	@Query(value = "UPDATE files SET name = ?2, updated_at = NOW() WHERE id = ?1 AND owner_id = ?3", nativeQuery = true)
	int renameFile(long fileId, String name, long ownerId);

	@Modifying
	@Transactional
	@Query(value = "UPDATE files SET folder_id = ?3, name = ?4, updated_at = NOW() WHERE id = ?1 AND owner_id = ?2",
			nativeQuery = true)
	int moveFile(long fileId, long ownerId, Long folderId, String name);

	@Query(value = "SELECT id, name, parent_id AS parentId, owner_id AS ownerId, created_at AS createdAt FROM folders WHERE id = ?1",
			nativeQuery = true)
	Optional<FolderRow> findFolder(long id);

	@Query(value = "SELECT id, name, parent_id AS parentId, owner_id AS ownerId, created_at AS createdAt FROM folders WHERE owner_id = ?1 AND parent_id IS NULL ORDER BY created_at DESC",
			nativeQuery = true)
	List<FolderRow> listRootFolders(long ownerId);

	@Query(value = "SELECT id, name, parent_id AS parentId, owner_id AS ownerId, created_at AS createdAt FROM folders WHERE owner_id = ?1 AND parent_id = ?2 ORDER BY created_at DESC",
			nativeQuery = true)
	List<FolderRow> listChildFolders(long ownerId, long parentId);

	@Modifying
	@Transactional
	@Query(value = "UPDATE folders SET name = ?2, updated_at = NOW() WHERE id = ?1 AND owner_id = ?3",
			nativeQuery = true)
	int renameFolder(long folderId, String name, long ownerId);

	@Modifying
	@Transactional
	@Query(value = "UPDATE folders SET parent_id = ?3, updated_at = NOW() WHERE id = ?1 AND owner_id = ?2",
			nativeQuery = true)
	int moveFolder(long folderId, long ownerId, Long parentId);

	@Query(value = "WITH RECURSIVE cte AS (SELECT id FROM folders WHERE id = ?1 UNION ALL SELECT f.id FROM folders f JOIN cte ON f.parent_id = cte.id) SELECT id FROM cte",
			nativeQuery = true)
	List<Long> collectChildIds(long folderId);

}