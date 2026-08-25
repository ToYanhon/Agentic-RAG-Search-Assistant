package com.clouddrive.repository;

import com.clouddrive.common.Errors;
import com.clouddrive.file.DeletionTask;
import com.clouddrive.file.Draft;
import com.clouddrive.file.FolderTree;
import com.clouddrive.file.ObjectDeletionQueue;
import com.clouddrive.file.QuotaReader;
import com.clouddrive.file.Record;
import com.clouddrive.file.Repository;
import com.clouddrive.repository.entity.FileEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件仓储 MySQL 实现，对应 Go db.FileRepository。 配额使用条件原子更新（最终权威）；唯一索引冲突重试一次。
 */
@org.springframework.stereotype.Repository
public class DbFileRepository
		implements Repository, ObjectDeletionQueue, FolderTree, QuotaReader, com.clouddrive.multipart.FolderOwner {

	private final EntityManager em;

	private final TransactionTemplate tx;

	public DbFileRepository(EntityManager em, TransactionTemplate tx) {
		this.em = em;
		this.tx = tx;
	}

	@Override
	public Record find(long id) {
		List<Object[]> rows = em.createNativeQuery(
				"SELECT id, owner_id, folder_id, name, size, mime_type, md5, object_key, created_at FROM files WHERE id = ?1")
			.setParameter(1, id)
			.getResultList();
		if (rows.isEmpty()) {
			throw new Errors.NotFound("file not found");
		}
		return toRecord(rows.get(0));
	}

	@Override
	public Record findByMd5Owner(long ownerId, String md5) {
		List<Object[]> rows = em.createNativeQuery(
				"SELECT id, owner_id, folder_id, name, size, mime_type, md5, object_key, created_at FROM files WHERE owner_id = ?1 AND md5 = ?2 ORDER BY id LIMIT 1")
			.setParameter(1, ownerId)
			.setParameter(2, md5)
			.getResultList();
		if (rows.isEmpty()) {
			throw new Errors.NotFound("file not found");
		}
		return toRecord(rows.get(0));
	}

	@Override
	public Record createWithQuota(Draft draft) {
		for (int attempt = 0; attempt < 2; attempt++) {
			try {
				return tx.execute(status -> createWithQuotaInner(draft));
			}
			catch (RuntimeException e) {
				if (!isDuplicateKey(e) || attempt == 1) {
					throw e;
				}
			}
		}
		throw new IllegalStateException("unreachable");
	}

	private Record createWithQuotaInner(Draft draft) {
		int affected = em.createNativeQuery(
				"UPDATE users SET storage_used = storage_used + ?1 WHERE id = ?2 AND storage_used + ?1 <= storage_limit")
			.setParameter(1, draft.size())
			.setParameter(2, draft.ownerId())
			.executeUpdate();
		if (affected == 0) {
			throw new Errors.StorageExceeded("storage limit exceeded");
		}
		String name = uniqueUploadName(draft.ownerId(), draft.folderId(), draft.name());
		FileEntity entity = new FileEntity();
		entity.setOwnerId(draft.ownerId());
		entity.setFolderId(draft.folderId());
		entity.setName(name);
		entity.setSize(draft.size());
		entity.setMimeType(draft.mimeType());
		entity.setMd5(draft.md5());
		entity.setObjectKey(draft.objectKey());
		em.persist(entity);
		em.flush();
		return new Record(entity.getId(), entity.getOwnerId(), entity.getFolderId(), entity.getName(), entity.getSize(),
				entity.getMimeType(), entity.getMd5(), entity.getObjectKey(), entity.getCreatedAt());
	}

	@Override
	public void deleteWithQuota(Record record) {
		tx.executeWithoutResult(status -> {
			em.createNativeQuery("DELETE FROM files WHERE id = ?1 AND owner_id = ?2")
				.setParameter(1, record.id())
				.setParameter(2, record.ownerId())
				.executeUpdate();
			em.createNativeQuery("UPDATE users SET storage_used = GREATEST(0, storage_used - ?1) WHERE id = ?2")
				.setParameter(1, record.size())
				.setParameter(2, record.ownerId())
				.executeUpdate();
			em.createNativeQuery("INSERT INTO object_delete_tasks (object_key) VALUES (?1)")
				.setParameter(1, record.objectKey())
				.executeUpdate();
		});
	}

	@Override
	public void updateContent(Record updated, long delta, String oldKey) {
		tx.executeWithoutResult(status -> {
			int affected = em.createNativeQuery(
					"UPDATE users SET storage_used = storage_used + ?1 WHERE id = ?2 AND storage_used + ?1 >= 0 AND storage_used + ?1 <= storage_limit")
				.setParameter(1, delta)
				.setParameter(2, updated.ownerId())
				.executeUpdate();
			if (affected == 0) {
				throw new Errors.StorageExceeded("storage limit exceeded");
			}
			affected = em.createNativeQuery(
					"UPDATE files SET size = ?1, mime_type = ?2, md5 = ?3, object_key = ?4, updated_at = NOW() WHERE id = ?5 AND owner_id = ?6")
				.setParameter(1, updated.size())
				.setParameter(2, updated.mimeType())
				.setParameter(3, updated.md5())
				.setParameter(4, updated.objectKey())
				.setParameter(5, updated.id())
				.setParameter(6, updated.ownerId())
				.executeUpdate();
			if (affected == 0) {
				throw new Errors.NotFound("file not found");
			}
			em.createNativeQuery("INSERT INTO object_delete_tasks (object_key) VALUES (?1)")
				.setParameter(1, oldKey)
				.executeUpdate();
		});
	}

	@Override
	public List<Record> filesInFolders(List<Long> folderIds) {
		if (folderIds.isEmpty()) {
			return List.of();
		}
		StringBuilder sql = new StringBuilder(
				"SELECT id, owner_id, folder_id, name, size, mime_type, md5, object_key, created_at FROM files WHERE folder_id IN (");
		for (int i = 0; i < folderIds.size(); i++) {
			if (i > 0) {
				sql.append(',');
			}
			sql.append('?').append(i + 1);
		}
		sql.append(')');
		Query query = em.createNativeQuery(sql.toString());
		for (int i = 0; i < folderIds.size(); i++) {
			query.setParameter(i + 1, folderIds.get(i));
		}
		List<?> rows = query.getResultList();
		List<Record> records = new ArrayList<>(rows.size());
		for (Object row : rows) {
			records.add(toRecord((Object[]) row));
		}
		return records;
	}

	@Override
	public void deleteFolderCascade(long ownerId, List<Long> folderIds, List<Record> files) {
		if (folderIds.isEmpty()) {
			return;
		}
		tx.executeWithoutResult(status -> {
			if (!files.isEmpty()) {
				StringBuilder ids = new StringBuilder("DELETE FROM files WHERE id IN (");
				for (int i = 0; i < files.size(); i++) {
					if (i > 0) {
						ids.append(',');
					}
					ids.append('?').append(i + 1);
				}
				ids.append(')');
				Query deleteFiles = em.createNativeQuery(ids.toString());
				long total = 0;
				for (int i = 0; i < files.size(); i++) {
					Record record = files.get(i);
					deleteFiles.setParameter(i + 1, record.id());
					total += record.size();
				}
				deleteFiles.executeUpdate();
				em.createNativeQuery("UPDATE users SET storage_used = GREATEST(0, storage_used - ?1) WHERE id = ?2")
					.setParameter(1, total)
					.setParameter(2, ownerId)
					.executeUpdate();
				for (Record record : files) {
					em.createNativeQuery("INSERT INTO object_delete_tasks (object_key) VALUES (?1)")
						.setParameter(1, record.objectKey())
						.executeUpdate();
				}
			}
			for (int i = folderIds.size() - 1; i >= 0; i--) {
				em.createNativeQuery("DELETE FROM folders WHERE id = ?1 AND owner_id = ?2")
					.setParameter(1, folderIds.get(i))
					.setParameter(2, ownerId)
					.executeUpdate();
			}
		});
	}

	@Override
	public void ensure() {
		em.createNativeQuery("""
				CREATE TABLE IF NOT EXISTS object_delete_tasks (
				    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
				    object_key VARCHAR(512) NOT NULL,
				    attempts INT NOT NULL DEFAULT 0,
				    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
				    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
				    INDEX idx_object_delete_tasks_due (next_attempt_at, id)
				) ENGINE=InnoDB""").executeUpdate();
	}

	@Override
	public List<DeletionTask> pending(int limit) {
		List<?> rows = em.createNativeQuery(
				"SELECT id, object_key FROM object_delete_tasks WHERE next_attempt_at <= NOW(6) ORDER BY id LIMIT ?1")
			.setParameter(1, limit)
			.getResultList();
		List<DeletionTask> tasks = new ArrayList<>(rows.size());
		for (Object row : rows) {
			Object[] values = (Object[]) row;
			tasks.add(new DeletionTask(((Number) values[0]).longValue(), (String) values[1]));
		}
		return tasks;
	}

	@Override
	public void complete(long id) {
		em.createNativeQuery("DELETE FROM object_delete_tasks WHERE id = ?1").setParameter(1, id).executeUpdate();
	}

	@Override
	public void retry(long id, int increment) {
		em.createNativeQuery(
				"UPDATE object_delete_tasks SET attempts = attempts + ?1, next_attempt_at = DATE_ADD(NOW(6), INTERVAL LEAST(30, POW(2, attempts + ?1)) SECOND) WHERE id = ?2")
			.setParameter(1, increment)
			.setParameter(2, id)
			.executeUpdate();
	}

	@Override
	public long findFolder(long folderId) {
		return findFolderOwner(folderId);
	}

	@Override
	public long findFolderOwner(long folderId) {
		List<?> rows = em.createNativeQuery("SELECT owner_id FROM folders WHERE id = ?1")
			.setParameter(1, folderId)
			.getResultList();
		if (rows.isEmpty()) {
			throw new Errors.NotFound("resource not found");
		}
		return ((Number) rows.get(0)).longValue();
	}

	@Override
	public List<Long> descendantIds(long folderId) {
		List<?> rows = em.createNativeQuery("""
				WITH RECURSIVE cte AS (SELECT id FROM folders WHERE id = ?1
				    UNION ALL SELECT f.id FROM folders f JOIN cte ON f.parent_id = cte.id)
				SELECT id FROM cte""").setParameter(1, folderId).getResultList();
		List<Long> ids = new ArrayList<>(rows.size());
		for (Object row : rows) {
			ids.add(((Number) row).longValue());
		}
		return ids;
	}

	@Override
	public Remaining remaining(long ownerId) {
		List<?> rows = em.createNativeQuery("SELECT storage_used, storage_limit FROM users WHERE id = ?1")
			.setParameter(1, ownerId)
			.getResultList();
		if (rows.isEmpty()) {
			return new Remaining(0, false);
		}
		Object[] row = (Object[]) rows.get(0);
		long used = ((Number) row[0]).longValue();
		long limit = ((Number) row[1]).longValue();
		return new Remaining(limit - used, true);
	}

	private String uniqueUploadName(long ownerId, Long folderId, String name) {
		boolean exists = nameTaken(ownerId, folderId, name);
		if (!exists) {
			return name;
		}
		int dot = name.lastIndexOf('.');
		String stem = name;
		String ext = "";
		if (dot >= 0) {
			stem = name.substring(0, dot);
			ext = name.substring(dot);
		}
		int start = 1;
		int open = stem.lastIndexOf('(');
		if (open >= 0 && stem.endsWith(")")) {
			try {
				int suffix = Integer.parseInt(stem.substring(open + 1, stem.length() - 1));
				stem = stem.substring(0, open);
				start = suffix + 1;
			}
			catch (NumberFormatException ignored) {
				// 非 (N) 后缀，保持原样
			}
		}
		for (int suffix = start;; suffix++) {
			String candidate = stem + "(" + suffix + ")" + ext;
			if (!nameTaken(ownerId, folderId, candidate)) {
				return candidate;
			}
		}
	}

	private boolean nameTaken(long ownerId, Long folderId, String name) {
		List<?> rows = em.createNativeQuery(
				"SELECT COUNT(*) FROM files WHERE owner_id = ?1 AND name = ?2 AND ((?3 IS NULL AND folder_id IS NULL) OR folder_id = ?3)")
			.setParameter(1, ownerId)
			.setParameter(2, name)
			.setParameter(3, folderId)
			.getResultList();
		return ((Number) rows.get(0)).longValue() > 0;
	}

	private static Record toRecord(Object[] row) {
		return new Record(((Number) row[0]).longValue(), ((Number) row[1]).longValue(),
				row[2] == null ? null : ((Number) row[2]).longValue(), (String) row[3], ((Number) row[4]).longValue(),
				(String) row[5], (String) row[6], (String) row[7],
				row[8] == null ? null : ((java.sql.Timestamp) row[8]).toLocalDateTime());
	}

	private static boolean isDuplicateKey(Throwable t) {
		Throwable current = t;
		while (current != null) {
			if (current instanceof SQLException sql) {
				return sql.getErrorCode() == 1062;
			}
			current = current.getCause();
		}
		return false;
	}

}