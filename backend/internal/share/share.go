// Package share 包含公开分享的领域规则与应用用例。
package share

import (
	"context"
	"errors"
	"io"
	"time"

	"github.com/clouddrive-ai/backend/internal/file"
)

var ErrNotFound = errors.New("share not found")

const cacheTTL = 5 * time.Minute

type Record struct {
	ID        int64
	FileID    int64
	OwnerID   int64
	Token     string
	ExpiresAt *time.Time
	CreatedAt time.Time
}

type Repository interface {
	Create(context.Context, int64, int64, string, *time.Time) (Record, error)
	FindByToken(context.Context, string) (Record, error)
	FindOwned(context.Context, int64, int64) (Record, error)
	Delete(context.Context, int64) error
}

type Files interface {
	Find(context.Context, int64) (file.Record, error)
}
type Objects interface {
	Get(context.Context, string) (io.ReadCloser, error)
	GetRange(context.Context, string, int64, int64) (io.ReadCloser, error)
}
type Clock interface{ Now() time.Time }
type TokenGenerator interface{ Generate(int) (string, error) }
type Cache interface {
	Get(context.Context, string) (Record, bool, error)
	Set(context.Context, string, Record, time.Duration) error
	Delete(context.Context, string) error
}

type Service struct {
	shares  Repository
	files   Files
	objects Objects
	clock   Clock
	tokens  TokenGenerator
	cache   Cache
}

func NewService(shares Repository, files Files, objects Objects, clock Clock, tokens TokenGenerator, cache Cache) *Service {
	return &Service{shares: shares, files: files, objects: objects, clock: clock, tokens: tokens, cache: cache}
}

func (s *Service) Create(ctx context.Context, ownerID, fileID int64, expireHours *int) (Record, error) {
	fileRecord, err := s.files.Find(ctx, fileID)
	if err != nil {
		return Record{}, err
	}
	if fileRecord.OwnerID != ownerID {
		return Record{}, file.ErrForbidden
	}
	token, err := s.tokens.Generate(32)
	if err != nil {
		return Record{}, err
	}
	var expiresAt *time.Time
	if expireHours != nil && *expireHours > 0 {
		value := s.clock.Now().Add(time.Duration(*expireHours) * time.Hour)
		expiresAt = &value
	}
	return s.shares.Create(ctx, ownerID, fileID, token, expiresAt)
}

func (s *Service) Revoke(ctx context.Context, ownerID, shareID int64) error {
	share, err := s.shares.FindOwned(ctx, shareID, ownerID)
	if err != nil {
		return err
	}
	if err := s.shares.Delete(ctx, shareID); err != nil {
		return err
	}
	return s.cache.Delete(ctx, cacheKey(share.Token))
}

func (s *Service) Access(ctx context.Context, token string) (file.Record, error) {
	share, err := s.validShare(ctx, token)
	if err != nil {
		return file.Record{}, err
	}
	return s.files.Find(ctx, share.FileID)
}

func (s *Service) Download(ctx context.Context, token string) (file.Record, io.ReadCloser, error) {
	fileRecord, err := s.Access(ctx, token)
	if err != nil {
		return file.Record{}, nil, err
	}
	body, err := s.objects.Get(ctx, fileRecord.ObjectKey)
	return fileRecord, body, err
}

func (s *Service) DownloadRange(ctx context.Context, token string, offset, length int64) (file.Record, io.ReadCloser, error) {
	fileRecord, err := s.Access(ctx, token)
	if err != nil {
		return file.Record{}, nil, err
	}
	body, err := s.objects.GetRange(ctx, fileRecord.ObjectKey, offset, length)
	return fileRecord, body, err
}

func (s *Service) validShare(ctx context.Context, token string) (Record, error) {
	share, hit, err := s.cache.Get(ctx, cacheKey(token))
	if err != nil {
		hit = false
	}
	if !hit {
		share, err = s.shares.FindByToken(ctx, token)
		if err != nil {
			return Record{}, err
		}
		if expired(share, s.clock.Now()) {
			return Record{}, ErrNotFound
		}
		if err := s.cache.Set(ctx, cacheKey(token), share, ttl(share, s.clock.Now())); err != nil {
			// 缓存故障不影响公开分享的 MySQL 权威读取。
		}
	}
	if expired(share, s.clock.Now()) {
		_ = s.cache.Delete(ctx, cacheKey(token))
		return Record{}, ErrNotFound
	}
	return share, nil
}

func cacheKey(token string) string { return "share:" + token }
func expired(share Record, now time.Time) bool {
	return share.ExpiresAt != nil && !share.ExpiresAt.After(now)
}
func ttl(share Record, now time.Time) time.Duration {
	if share.ExpiresAt == nil {
		return cacheTTL
	}
	remaining := share.ExpiresAt.Sub(now)
	if remaining < cacheTTL {
		return remaining
	}
	return cacheTTL
}
