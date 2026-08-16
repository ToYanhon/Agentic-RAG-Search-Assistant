// Package db contains explicit MySQL adapters for the M1 auth slice.
package db

import (
	"context"
	"database/sql"
	"errors"
	"strings"
	"time"

	"github.com/clouddrive-ai/backend/internal/auth"
	"github.com/go-sql-driver/mysql"
)

type UserRepository struct{ db *sql.DB }

func (r UserRepository) Remaining(ctx context.Context, ownerID int64) (int64, bool, error) {
	var used, limit int64
	err := r.db.QueryRowContext(ctx, `SELECT storage_used, storage_limit FROM users WHERE id = ?`, ownerID).Scan(&used, &limit)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, false, auth.ErrNotFound
	}
	if err != nil {
		return 0, false, err
	}
	return limit - used, true, nil
}

func NewUserRepository(db *sql.DB) UserRepository { return UserRepository{db: db} }

func (r UserRepository) FindByID(ctx context.Context, id int64) (auth.User, error) {
	return r.find(ctx, "SELECT id, username, email, password, storage_used, storage_limit, created_at FROM users WHERE id = ?", id)
}
func (r UserRepository) FindByUsername(ctx context.Context, username string) (auth.User, error) {
	return r.find(ctx, "SELECT id, username, email, password, storage_used, storage_limit, created_at FROM users WHERE username = ?", username)
}
func (r UserRepository) FindByEmail(ctx context.Context, email string) (auth.User, error) {
	return r.find(ctx, "SELECT id, username, email, password, storage_used, storage_limit, created_at FROM users WHERE email = ?", email)
}
func (r UserRepository) find(ctx context.Context, query string, argument any) (auth.User, error) {
	var user auth.User
	var createdAt sql.NullTime
	err := r.db.QueryRowContext(ctx, query, argument).Scan(&user.ID, &user.Username, &user.Email, &user.Password, &user.StorageUsed, &user.StorageLimit, &createdAt)
	if errors.Is(err, sql.ErrNoRows) {
		return auth.User{}, auth.ErrNotFound
	}
	if err != nil {
		return auth.User{}, err
	}
	if createdAt.Valid {
		user.CreatedAt = createdAt.Time
	} else {
		user.CreatedAt = time.Time{}
	}
	return user, nil
}
func (r UserRepository) Create(ctx context.Context, username, email, password string) error {
	_, err := r.db.ExecContext(ctx, "INSERT INTO users (username, email, password, storage_used, storage_limit, created_at, updated_at) VALUES (?, ?, ?, 0, 1073741824, NOW(), NOW())", username, email, password)
	return duplicateError(err)
}
func (r UserRepository) UpdateUsername(ctx context.Context, id int64, username string) error {
	result, err := r.db.ExecContext(ctx, "UPDATE users SET username = ?, updated_at = NOW() WHERE id = ?", username, id)
	if err = duplicateError(err); err != nil {
		return err
	}
	changed, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if changed == 0 {
		return auth.ErrNotFound
	}
	return nil
}
func (r UserRepository) UpdatePassword(ctx context.Context, id int64, password string) error {
	result, err := r.db.ExecContext(ctx, "UPDATE users SET password = ?, updated_at = NOW() WHERE id = ?", password, id)
	if err != nil {
		return err
	}
	changed, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if changed == 0 {
		return auth.ErrNotFound
	}
	return nil
}
func duplicateError(err error) error {
	if err == nil {
		return nil
	}
	var mysqlError *mysql.MySQLError
	if !errors.As(err, &mysqlError) || mysqlError.Number != 1062 {
		return err
	}
	message := strings.ToLower(mysqlError.Message)
	if strings.Contains(message, "uk_users_email") || strings.Contains(message, "email") {
		return auth.ErrEmailTaken
	}
	if strings.Contains(message, "uk_users_username") || strings.Contains(message, "username") {
		return auth.ErrUsernameTaken
	}
	return auth.ErrDuplicateUser
}
