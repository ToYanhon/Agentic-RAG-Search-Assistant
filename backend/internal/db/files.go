package db

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/clouddrive-ai/backend/internal/auth"
	"github.com/clouddrive-ai/backend/internal/file"
	"github.com/go-sql-driver/mysql"
)

type FileRepository struct{ db *sql.DB }

func NewFileRepository(db *sql.DB) FileRepository { return FileRepository{db: db} }

func (r FileRepository) Remaining(ctx context.Context, ownerID int64) (int64, bool, error) {
	var used, limit int64
	err := r.db.QueryRowContext(ctx, `SELECT storage_used, storage_limit FROM users WHERE id = ?`, ownerID).Scan(&used, &limit)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, false, nil
	}
	if err != nil {
		return 0, false, err
	}
	return limit - used, true, nil
}

func (r FileRepository) Ensure(ctx context.Context) error {
	_, err := r.db.ExecContext(ctx, `CREATE TABLE IF NOT EXISTS object_delete_tasks (
		id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
		object_key VARCHAR(512) NOT NULL,
		attempts INT NOT NULL DEFAULT 0,
		next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
		created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
		INDEX idx_object_delete_tasks_due (next_attempt_at, id)
	) ENGINE=InnoDB`)
	return err
}

func (r FileRepository) Find(ctx context.Context, id int64) (file.Record, error) {
	var value file.Record
	var folder sql.NullInt64
	err := r.db.QueryRowContext(ctx, `SELECT id, owner_id, folder_id, name, size, mime_type, md5, object_key, created_at FROM files WHERE id = ?`, id).Scan(&value.ID, &value.OwnerID, &folder, &value.Name, &value.Size, &value.MimeType, &value.MD5, &value.ObjectKey, &value.CreatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return file.Record{}, auth.ErrNotFound
	}
	if err != nil {
		return file.Record{}, err
	}
	value.FolderID = nullableID(folder)
	return value, nil
}

func (r FileRepository) FindByMD5Owner(ctx context.Context, ownerID int64, md5 string) (file.Record, error) {
	var record file.Record
	var folder sql.NullInt64
	err := r.db.QueryRowContext(ctx, `SELECT id, owner_id, folder_id, name, size, mime_type, md5, object_key, created_at FROM files WHERE owner_id = ? AND md5 = ? ORDER BY id LIMIT 1`, ownerID, md5).Scan(&record.ID, &record.OwnerID, &folder, &record.Name, &record.Size, &record.MimeType, &record.MD5, &record.ObjectKey, &record.CreatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return file.Record{}, file.ErrNotFound
	}
	if err != nil {
		return file.Record{}, err
	}
	record.FolderID = nullableID(folder)
	return record, nil
}

func (r FileRepository) CreateWithQuota(ctx context.Context, draft file.Draft) (file.Record, error) {
	for attempt := 0; attempt < 2; attempt++ {
		record, err := r.createWithQuota(ctx, draft)
		if !isDuplicateKey(err) || attempt == 1 {
			return record, err
		}
	}
	return file.Record{}, nil
}

// createWithQuota 每次使用独立事务，避免唯一索引重试复用已回滚的事务。
func (r FileRepository) createWithQuota(ctx context.Context, draft file.Draft) (file.Record, error) {
	tx, err := r.db.BeginTx(ctx, nil)
	if err != nil {
		return file.Record{}, err
	}
	defer tx.Rollback()
	result, err := tx.ExecContext(ctx, `UPDATE users SET storage_used = storage_used + ? WHERE id = ? AND storage_used + ? <= storage_limit`, draft.Size, draft.OwnerID, draft.Size)
	if err != nil {
		return file.Record{}, err
	}
	affected, err := result.RowsAffected()
	if err != nil {
		return file.Record{}, err
	}
	if affected == 0 {
		return file.Record{}, file.ErrStorageExceeded
	}
	name, err := uniqueUploadName(ctx, tx, draft.OwnerID, draft.FolderID, draft.Name)
	if err != nil {
		return file.Record{}, err
	}
	result, err = tx.ExecContext(ctx, `INSERT INTO files (name,size,mime_type,md5,object_key,folder_id,owner_id,created_at,updated_at) VALUES (?,?,?,?,?,?,?,NOW(),NOW())`, name, draft.Size, draft.MimeType, draft.MD5, draft.ObjectKey, draft.FolderID, draft.OwnerID)
	if err != nil {
		return file.Record{}, err
	}
	id, err := result.LastInsertId()
	if err != nil {
		return file.Record{}, err
	}
	if err := tx.Commit(); err != nil {
		return file.Record{}, err
	}
	return file.Record{ID: id, OwnerID: draft.OwnerID, FolderID: draft.FolderID, Name: name, Size: draft.Size, MimeType: draft.MimeType, MD5: draft.MD5, ObjectKey: draft.ObjectKey, CreatedAt: time.Now()}, nil
}

func (r FileRepository) DeleteWithQuota(ctx context.Context, record file.Record) error {
	tx, err := r.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(ctx, `DELETE FROM files WHERE id = ? AND owner_id = ?`, record.ID, record.OwnerID); err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, `UPDATE users SET storage_used = GREATEST(0, storage_used - ?) WHERE id = ?`, record.Size, record.OwnerID); err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, `INSERT INTO object_delete_tasks (object_key) VALUES (?)`, record.ObjectKey); err != nil {
		return err
	}
	return tx.Commit()
}

func (r FileRepository) UpdateContent(ctx context.Context, updated file.Record, delta int64, oldKey string) error {
	tx, err := r.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	result, err := tx.ExecContext(ctx, `UPDATE users SET storage_used = storage_used + ? WHERE id = ? AND storage_used + ? >= 0 AND storage_used + ? <= storage_limit`, delta, updated.OwnerID, delta, delta)
	if err != nil {
		return err
	}
	changed, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if changed == 0 {
		return file.ErrStorageExceeded
	}
	result, err = tx.ExecContext(ctx, `UPDATE files SET size = ?, mime_type = ?, md5 = ?, object_key = ?, updated_at = NOW() WHERE id = ? AND owner_id = ?`, updated.Size, updated.MimeType, updated.MD5, updated.ObjectKey, updated.ID, updated.OwnerID)
	if err != nil {
		return err
	}
	changed, err = result.RowsAffected()
	if err != nil {
		return err
	}
	if changed == 0 {
		return file.ErrNotFound
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO object_delete_tasks (object_key) VALUES (?)`, oldKey); err != nil {
		return err
	}
	return tx.Commit()
}

func (r FileRepository) FindFolder(ctx context.Context, id int64) (int64, error) {
	var owner int64
	err := r.db.QueryRowContext(ctx, `SELECT owner_id FROM folders WHERE id = ?`, id).Scan(&owner)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, auth.ErrNotFound
	}
	return owner, err
}

func (r FileRepository) DescendantIDs(ctx context.Context, folderID int64) ([]int64, error) {
	rows, err := r.db.QueryContext(ctx, `WITH RECURSIVE cte AS (SELECT id FROM folders WHERE id = ? UNION ALL SELECT f.id FROM folders f JOIN cte ON f.parent_id = cte.id) SELECT id FROM cte`, folderID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	ids := make([]int64, 0)
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}

func (r FileRepository) FilesInFolders(ctx context.Context, folderIDs []int64) ([]file.Record, error) {
	if len(folderIDs) == 0 {
		return []file.Record{}, nil
	}
	query, args := inQuery(`SELECT id, owner_id, folder_id, name, size, mime_type, md5, object_key, created_at FROM files WHERE folder_id IN`, folderIDs)
	rows, err := r.db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	files := make([]file.Record, 0)
	for rows.Next() {
		var record file.Record
		var folder sql.NullInt64
		if err := rows.Scan(&record.ID, &record.OwnerID, &folder, &record.Name, &record.Size, &record.MimeType, &record.MD5, &record.ObjectKey, &record.CreatedAt); err != nil {
			return nil, err
		}
		record.FolderID = nullableID(folder)
		files = append(files, record)
	}
	return files, rows.Err()
}

func (r FileRepository) DeleteFolderCascade(ctx context.Context, ownerID int64, folderIDs []int64, files []file.Record) error {
	if len(folderIDs) == 0 {
		return nil
	}
	tx, err := r.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if len(files) > 0 {
		fileIDs := make([]int64, 0, len(files))
		var total int64
		for _, record := range files {
			fileIDs = append(fileIDs, record.ID)
			total += record.Size
		}
		query, args := inQuery(`DELETE FROM files WHERE id IN`, fileIDs)
		if _, err := tx.ExecContext(ctx, query, args...); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `UPDATE users SET storage_used = GREATEST(0, storage_used - ?) WHERE id = ?`, total, ownerID); err != nil {
			return err
		}
		for _, record := range files {
			if _, err := tx.ExecContext(ctx, `INSERT INTO object_delete_tasks (object_key) VALUES (?)`, record.ObjectKey); err != nil {
				return err
			}
		}
	}
	// The recursive query returns parents before descendants. Delete bottom-up
	// so MySQL's parent_id foreign key cannot reject a parent before its child.
	for i := len(folderIDs) - 1; i >= 0; i-- {
		if _, err := tx.ExecContext(ctx, `DELETE FROM folders WHERE id = ? AND owner_id = ?`, folderIDs[i], ownerID); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func (r FileRepository) Pending(ctx context.Context, limit int) ([]file.DeletionTask, error) {
	rows, err := r.db.QueryContext(ctx, `SELECT id, object_key FROM object_delete_tasks WHERE next_attempt_at <= NOW(6) ORDER BY id LIMIT ?`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	tasks := make([]file.DeletionTask, 0)
	for rows.Next() {
		var task file.DeletionTask
		if err := rows.Scan(&task.ID, &task.ObjectKey); err != nil {
			return nil, err
		}
		tasks = append(tasks, task)
	}
	return tasks, rows.Err()
}

func (r FileRepository) Complete(ctx context.Context, id int64) error {
	_, err := r.db.ExecContext(ctx, `DELETE FROM object_delete_tasks WHERE id = ?`, id)
	return err
}

func (r FileRepository) Retry(ctx context.Context, id int64, increment int) error {
	_, err := r.db.ExecContext(ctx, `UPDATE object_delete_tasks SET attempts = attempts + ?, next_attempt_at = DATE_ADD(NOW(6), INTERVAL LEAST(30, POW(2, attempts + ?)) SECOND) WHERE id = ?`, increment, increment, id)
	return err
}

func inQuery(prefix string, ids []int64) (string, []any) {
	args := make([]any, len(ids))
	marks := make([]string, len(ids))
	for i, id := range ids {
		args[i], marks[i] = id, "?"
	}
	return fmt.Sprintf("%s (%s)", prefix, strings.Join(marks, ",")), args
}

func isDuplicateKey(err error) bool {
	var mysqlErr *mysql.MySQLError
	return errors.As(err, &mysqlErr) && mysqlErr.Number == 1062
}

// uniqueUploadName 与 catalog 的重命名、移动共用相同后缀策略。
func uniqueUploadName(ctx context.Context, tx *sql.Tx, ownerID int64, folderID *int64, name string) (string, error) {
	taken := func(candidate string) (bool, error) {
		var count int
		err := tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM files WHERE owner_id = ? AND name = ? AND ((? IS NULL AND folder_id IS NULL) OR folder_id = ?)`, ownerID, candidate, folderID, folderID).Scan(&count)
		return count > 0, err
	}
	exists, err := taken(name)
	if err != nil || !exists {
		return name, err
	}
	dot := strings.LastIndex(name, ".")
	stem, ext := name, ""
	if dot >= 0 {
		stem, ext = name[:dot], name[dot:]
	}
	start := 1
	if open := strings.LastIndex(stem, "("); open >= 0 && strings.HasSuffix(stem, ")") {
		if suffix, parseErr := strconv.Atoi(stem[open+1 : len(stem)-1]); parseErr == nil {
			stem, start = stem[:open], suffix+1
		}
	}
	for suffix := start; ; suffix++ {
		candidate := stem + "(" + strconv.Itoa(suffix) + ")" + ext
		exists, err := taken(candidate)
		if err != nil || !exists {
			return candidate, err
		}
	}
}
