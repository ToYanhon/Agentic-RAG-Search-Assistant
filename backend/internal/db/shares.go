package db

import (
	"context"
	"database/sql"
	"errors"
	"time"

	"github.com/clouddrive-ai/backend/internal/share"
)

type ShareRepository struct{ db *sql.DB }

func NewShareRepository(db *sql.DB) ShareRepository { return ShareRepository{db: db} }

func (r ShareRepository) Create(ctx context.Context, ownerID, fileID int64, token string, expiresAt *time.Time) (share.Record, error) {
	result, err := r.db.ExecContext(ctx, `INSERT INTO shares (owner_id, file_id, token, expired_at, created_at) VALUES (?, ?, ?, ?, NOW())`, ownerID, fileID, token, expiresAt)
	if err != nil {
		return share.Record{}, err
	}
	id, err := result.LastInsertId()
	if err != nil {
		return share.Record{}, err
	}
	return share.Record{ID: id, OwnerID: ownerID, FileID: fileID, Token: token, ExpiresAt: expiresAt, CreatedAt: time.Now()}, nil
}

func (r ShareRepository) FindByToken(ctx context.Context, token string) (share.Record, error) {
	var value share.Record
	var expires sql.NullTime
	var created sql.NullTime
	err := r.db.QueryRowContext(ctx, `SELECT id, file_id, owner_id, token, expired_at, created_at FROM shares WHERE token = ?`, token).Scan(&value.ID, &value.FileID, &value.OwnerID, &value.Token, &expires, &created)
	if errors.Is(err, sql.ErrNoRows) {
		return share.Record{}, share.ErrNotFound
	}
	if err != nil {
		return share.Record{}, err
	}
	if expires.Valid {
		value.ExpiresAt = &expires.Time
	}
	if created.Valid {
		value.CreatedAt = created.Time
	}
	return value, nil
}

func (r ShareRepository) FindOwned(ctx context.Context, id, ownerID int64) (share.Record, error) {
	var value share.Record
	var expires sql.NullTime
	var created sql.NullTime
	err := r.db.QueryRowContext(ctx, `SELECT id, file_id, owner_id, token, expired_at, created_at FROM shares WHERE id = ? AND owner_id = ?`, id, ownerID).Scan(&value.ID, &value.FileID, &value.OwnerID, &value.Token, &expires, &created)
	if errors.Is(err, sql.ErrNoRows) {
		return share.Record{}, share.ErrNotFound
	}
	if err != nil {
		return share.Record{}, err
	}
	if expires.Valid {
		value.ExpiresAt = &expires.Time
	}
	if created.Valid {
		value.CreatedAt = created.Time
	}
	return value, nil
}

func (r ShareRepository) Delete(ctx context.Context, id int64) error {
	result, err := r.db.ExecContext(ctx, `DELETE FROM shares WHERE id = ?`, id)
	if err != nil {
		return err
	}
	changed, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if changed == 0 {
		return share.ErrNotFound
	}
	return nil
}
