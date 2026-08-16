package db

import (
	"context"
	"database/sql"
	"errors"
	"time"

	"github.com/clouddrive-ai/backend/internal/auth"
	"github.com/clouddrive-ai/backend/internal/catalog"
)

type CatalogQuery struct{ db *sql.DB }

func NewCatalogQuery(db *sql.DB) CatalogQuery { return CatalogQuery{db: db} }

func (q CatalogQuery) CreateFolder(ctx context.Context, ownerID int64, parentID *int64, name string) (catalog.Folder, error) {
	result, err := q.db.ExecContext(ctx, `INSERT INTO folders (name, parent_id, owner_id, created_at, updated_at) VALUES (?, ?, ?, NOW(), NOW())`, name, parentID, ownerID)
	if err != nil {
		return catalog.Folder{}, err
	}
	id, err := result.LastInsertId()
	if err != nil {
		return catalog.Folder{}, err
	}
	return q.FindFolder(ctx, id)
}

func (q CatalogQuery) RenameFolder(ctx context.Context, ownerID, folderID int64, name string) (bool, error) {
	result, err := q.db.ExecContext(ctx, `UPDATE folders SET name = ?, updated_at = NOW() WHERE id = ? AND owner_id = ?`, name, folderID, ownerID)
	if err != nil {
		return false, err
	}
	changed, err := result.RowsAffected()
	return changed > 0, err
}

func (q CatalogQuery) MoveFolder(ctx context.Context, ownerID, folderID int64, parentID *int64) (bool, error) {
	result, err := q.db.ExecContext(ctx, `UPDATE folders SET parent_id = ?, updated_at = NOW() WHERE id = ? AND owner_id = ?`, parentID, folderID, ownerID)
	if err != nil {
		return false, err
	}
	changed, err := result.RowsAffected()
	return changed > 0, err
}

func (q CatalogQuery) CollectChildIDs(ctx context.Context, folderID int64) ([]int64, error) {
	rows, err := q.db.QueryContext(ctx, `WITH RECURSIVE cte AS (SELECT id FROM folders WHERE id = ? UNION ALL SELECT f.id FROM folders f JOIN cte ON f.parent_id = cte.id) SELECT id FROM cte`, folderID)
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

func (q CatalogQuery) NameTaken(ctx context.Context, ownerID int64, folderID *int64, name string, excludeID int64) (bool, error) {
	var count int
	err := q.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM files WHERE owner_id = ? AND name = ? AND ((? IS NULL AND folder_id IS NULL) OR folder_id = ?) AND id <> ?`, ownerID, name, folderID, folderID, excludeID).Scan(&count)
	return count > 0, err
}

func (q CatalogQuery) RenameFile(ctx context.Context, ownerID, fileID int64, name string) (bool, error) {
	result, err := q.db.ExecContext(ctx, `UPDATE files SET name = ?, updated_at = NOW() WHERE id = ? AND owner_id = ?`, name, fileID, ownerID)
	if err != nil {
		return false, err
	}
	changed, err := result.RowsAffected()
	return changed > 0, err
}

func (q CatalogQuery) MoveFile(ctx context.Context, ownerID, fileID int64, folderID *int64, name string) (bool, error) {
	result, err := q.db.ExecContext(ctx, `UPDATE files SET folder_id = ?, name = ?, updated_at = NOW() WHERE id = ? AND owner_id = ?`, folderID, name, fileID, ownerID)
	if err != nil {
		return false, err
	}
	changed, err := result.RowsAffected()
	return changed > 0, err
}

func (q CatalogQuery) FindFile(ctx context.Context, id int64) (catalog.File, error) {
	var file catalog.File
	var folderID sql.NullInt64
	var created sql.NullTime
	err := q.db.QueryRowContext(ctx, `SELECT id, name, size, mime_type, md5, folder_id, owner_id, created_at FROM files WHERE id = ?`, id).
		Scan(&file.ID, &file.Name, &file.Size, &file.MimeType, &file.MD5, &folderID, &file.OwnerID, &created)
	if errors.Is(err, sql.ErrNoRows) {
		return catalog.File{}, auth.ErrNotFound
	}
	if err != nil {
		return catalog.File{}, err
	}
	file.FolderID, file.CreatedAt = nullableID(folderID), formatTime(nullableTime(created))
	return file, nil
}

func (q CatalogQuery) ListFiles(ctx context.Context, ownerID int64, page, pageSize int) ([]catalog.File, int64, error) {
	var total int64
	if err := q.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM files WHERE owner_id = ?`, ownerID).Scan(&total); err != nil {
		return nil, 0, err
	}
	files, err := q.listFiles(ctx, `WHERE owner_id = ?`, []any{ownerID}, page, pageSize)
	return files, total, err
}

func (q CatalogQuery) SearchFiles(ctx context.Context, ownerID int64, term string, page, pageSize int) ([]catalog.File, int64, error) {
	pattern := "%" + term + "%"
	var total int64
	if err := q.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM files WHERE owner_id = ? AND name LIKE ?`, ownerID, pattern).Scan(&total); err != nil {
		return nil, 0, err
	}
	files, err := q.listFiles(ctx, `WHERE owner_id = ? AND name LIKE ?`, []any{ownerID, pattern}, page, pageSize)
	return files, total, err
}

func (q CatalogQuery) ListFolderFiles(ctx context.Context, folderID, ownerID int64) ([]catalog.File, error) {
	return q.listFiles(ctx, `WHERE folder_id = ? AND owner_id = ?`, []any{folderID, ownerID}, 1, 100000)
}

func (q CatalogQuery) listFiles(ctx context.Context, clause string, args []any, page, pageSize int) ([]catalog.File, error) {
	queryArgs := append(args, (page-1)*pageSize, pageSize)
	rows, err := q.db.QueryContext(ctx, `SELECT id, name, size, mime_type, md5, folder_id, owner_id, created_at FROM files `+clause+` ORDER BY created_at DESC LIMIT ?, ?`, queryArgs...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	files := make([]catalog.File, 0)
	for rows.Next() {
		var file catalog.File
		var folderID sql.NullInt64
		var created sql.NullTime
		if err := rows.Scan(&file.ID, &file.Name, &file.Size, &file.MimeType, &file.MD5, &folderID, &file.OwnerID, &created); err != nil {
			return nil, err
		}
		file.FolderID, file.CreatedAt = nullableID(folderID), formatTime(nullableTime(created))
		files = append(files, file)
	}
	return files, rows.Err()
}

func (q CatalogQuery) FindFolder(ctx context.Context, id int64) (catalog.Folder, error) {
	var folder catalog.Folder
	var parentID sql.NullInt64
	var created sql.NullTime
	err := q.db.QueryRowContext(ctx, `SELECT id, name, parent_id, owner_id, created_at FROM folders WHERE id = ?`, id).
		Scan(&folder.ID, &folder.Name, &parentID, &folder.OwnerID, &created)
	if errors.Is(err, sql.ErrNoRows) {
		return catalog.Folder{}, auth.ErrNotFound
	}
	if err != nil {
		return catalog.Folder{}, err
	}
	folder.ParentID, folder.CreatedAt = nullableID(parentID), nullableTime(created)
	return folder, nil
}

func (q CatalogQuery) ListRootFolders(ctx context.Context, ownerID int64) ([]catalog.Folder, error) {
	return q.listFolders(ctx, `WHERE owner_id = ? AND parent_id IS NULL`, ownerID)
}

func (q CatalogQuery) ListChildFolders(ctx context.Context, ownerID, parentID int64) ([]catalog.Folder, error) {
	return q.listFolders(ctx, `WHERE owner_id = ? AND parent_id = ?`, ownerID, parentID)
}

func (q CatalogQuery) listFolders(ctx context.Context, clause string, args ...any) ([]catalog.Folder, error) {
	rows, err := q.db.QueryContext(ctx, `SELECT id, name, parent_id, owner_id, created_at FROM folders `+clause+` ORDER BY created_at DESC`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	folders := make([]catalog.Folder, 0)
	for rows.Next() {
		var folder catalog.Folder
		var parentID sql.NullInt64
		var created sql.NullTime
		if err := rows.Scan(&folder.ID, &folder.Name, &parentID, &folder.OwnerID, &created); err != nil {
			return nil, err
		}
		folder.ParentID, folder.CreatedAt = nullableID(parentID), nullableTime(created)
		folders = append(folders, folder)
	}
	return folders, rows.Err()
}

func nullableID(value sql.NullInt64) *int64 {
	if !value.Valid {
		return nil
	}
	return &value.Int64
}

func nullableTime(value sql.NullTime) time.Time {
	if !value.Valid {
		return time.Time{}
	}
	return value.Time
}

func formatTime(value time.Time) string {
	if value.IsZero() {
		return ""
	}
	return value.UTC().Format("2006-01-02T15:04:05Z")
}
