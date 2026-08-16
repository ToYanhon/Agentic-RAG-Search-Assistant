package db

import (
	"context"
	"database/sql"
	"errors"

	"github.com/clouddrive-ai/backend/internal/llmconfig"
)

type LLMConfigRepository struct{ db *sql.DB }

func NewLLMConfigRepository(db *sql.DB) LLMConfigRepository { return LLMConfigRepository{db: db} }
func (r LLMConfigRepository) Find(ctx context.Context, userID int64, provider string) (llmconfig.Stored, error) {
	var v llmconfig.Stored
	var updated sql.NullTime
	err := r.db.QueryRowContext(ctx, `SELECT provider,base_url,api_key_enc,model,updated_at FROM llm_configs WHERE user_id=? AND provider=?`, userID, provider).Scan(&v.Provider, &v.BaseURL, &v.APIKeyEnc, &v.Model, &updated)
	if errors.Is(err, sql.ErrNoRows) {
		return llmconfig.Stored{}, llmconfig.ErrNotFound
	}
	if err != nil {
		return llmconfig.Stored{}, err
	}
	if updated.Valid {
		v.UpdatedAt = updated.Time
	}
	return v, nil
}
func (r LLMConfigRepository) FindAll(ctx context.Context, userID int64) ([]llmconfig.Stored, error) {
	rows, err := r.db.QueryContext(ctx, `SELECT provider,base_url,api_key_enc,model,updated_at FROM llm_configs WHERE user_id=? ORDER BY provider`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	values := make([]llmconfig.Stored, 0)
	for rows.Next() {
		var v llmconfig.Stored
		var updated sql.NullTime
		if err := rows.Scan(&v.Provider, &v.BaseURL, &v.APIKeyEnc, &v.Model, &updated); err != nil {
			return nil, err
		}
		if updated.Valid {
			v.UpdatedAt = updated.Time
		}
		values = append(values, v)
	}
	return values, rows.Err()
}
func (r LLMConfigRepository) Upsert(ctx context.Context, userID int64, provider, baseURL, apiKeyEnc, model string) error {
	_, err := r.db.ExecContext(ctx, `INSERT INTO llm_configs (user_id,provider,base_url,api_key_enc,model,updated_at) VALUES (?,?,?,?,?,NOW()) ON DUPLICATE KEY UPDATE base_url=VALUES(base_url),api_key_enc=VALUES(api_key_enc),model=VALUES(model),updated_at=NOW()`, userID, provider, baseURL, apiKeyEnc, model)
	return err
}
func (r LLMConfigRepository) Delete(ctx context.Context, userID int64, provider string) error {
	_, err := r.db.ExecContext(ctx, `DELETE FROM llm_configs WHERE user_id=? AND provider=?`, userID, provider)
	return err
}
