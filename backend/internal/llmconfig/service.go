// Package llmconfig 管理用户按供应商隔离的 LLM 配置。
package llmconfig

import (
	"context"
	"errors"
	"strings"
	"time"
)

var ErrProviderRequired = errors.New("provider required")
var ErrProviderTooLong = errors.New("provider too long")

type Stored struct {
	Provider  string
	BaseURL   string
	APIKeyEnc string
	Model     string
	UpdatedAt time.Time
}

type View struct {
	Provider     string `json:"provider"`
	BaseURL      string `json:"base_url"`
	APIKeyMasked string `json:"api_key_masked"`
	Model        string `json:"model"`
	Configured   bool   `json:"configured"`
	UpdatedAt    string `json:"updated_at"`
}

type Repository interface {
	Find(context.Context, int64, string) (Stored, error)
	FindAll(context.Context, int64) ([]Stored, error)
	Upsert(context.Context, int64, string, string, string, string) error
	Delete(context.Context, int64, string) error
}
type Secret interface {
	Encrypt(string) (string, error)
	Decrypt(string) (string, error)
}

type Service struct {
	configs Repository
	secrets Secret
}

func NewService(configs Repository, secrets Secret) *Service {
	return &Service{configs: configs, secrets: secrets}
}

func (s *Service) Upsert(ctx context.Context, userID int64, provider, baseURL, apiKey, model string) error {
	provider = strings.TrimSpace(provider)
	if provider == "" {
		return ErrProviderRequired
	}
	if len(provider) > 64 {
		return ErrProviderTooLong
	}
	enc := ""
	existing, err := s.configs.Find(ctx, userID, provider)
	if err == nil {
		enc = existing.APIKeyEnc
	} else if !errors.Is(err, ErrNotFound) {
		return err
	}
	if apiKey != "" {
		enc, err = s.secrets.Encrypt(apiKey)
		if err != nil {
			return err
		}
	}
	return s.configs.Upsert(ctx, userID, provider, NormalizeBaseURL(baseURL), enc, strings.TrimSpace(model))
}

func (s *Service) List(ctx context.Context, userID int64) ([]View, error) {
	stored, err := s.configs.FindAll(ctx, userID)
	if err != nil {
		return nil, err
	}
	views := make([]View, 0, len(stored))
	for _, value := range stored {
		baseURL := NormalizeBaseURL(value.BaseURL)
		masked := ""
		if value.APIKeyEnc != "" {
			masked = "******"
		}
		updated := ""
		if !value.UpdatedAt.IsZero() {
			updated = value.UpdatedAt.UTC().Format("2006-01-02T15:04:05Z")
		}
		views = append(views, View{Provider: value.Provider, BaseURL: baseURL, APIKeyMasked: masked, Model: value.Model, Configured: baseURL != "" && masked != "" && strings.TrimSpace(value.Model) != "", UpdatedAt: updated})
	}
	return views, nil
}

func (s *Service) Delete(ctx context.Context, userID int64, provider string) error {
	provider = strings.TrimSpace(provider)
	if provider == "" {
		return ErrProviderRequired
	}
	return s.configs.Delete(ctx, userID, provider)
}

type Resolved struct {
	BaseURL string
	APIKey  string
	Model   string
	OK      bool
}

// Resolve 仅供受控代理注入请求头，解密失败时不泄露配置或错误细节。
func (s *Service) Resolve(ctx context.Context, userID int64, provider string) Resolved {
	stored, err := s.configs.Find(ctx, userID, provider)
	if err != nil || stored.APIKeyEnc == "" {
		return Resolved{}
	}
	key, err := s.secrets.Decrypt(stored.APIKeyEnc)
	if err != nil {
		return Resolved{}
	}
	return Resolved{BaseURL: NormalizeBaseURL(stored.BaseURL), APIKey: key, Model: stored.Model, OK: true}
}

func NormalizeBaseURL(raw string) string {
	value := strings.TrimSpace(raw)
	if index := strings.Index(value, "?"); index >= 0 {
		value = value[:index]
	}
	value = strings.TrimRight(value, "/")
	const suffix = "/chat/completions"
	if strings.EqualFold(strings.TrimSuffix(value, "/"), strings.TrimSuffix(suffix, "/")) {
		return ""
	}
	if len(value) >= len(suffix) && strings.EqualFold(value[len(value)-len(suffix):], suffix) {
		value = strings.TrimRight(value[:len(value)-len(suffix)], "/")
	}
	return value
}

var ErrNotFound = errors.New("llm config not found")
