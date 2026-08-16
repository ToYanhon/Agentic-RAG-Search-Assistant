package share

import (
	"context"
	"errors"
	"io"
	"testing"
	"time"

	"github.com/clouddrive-ai/backend/internal/file"
)

func TestCreateRejectsForeignFile(t *testing.T) {
	service := NewService(fakeShares{}, fakeFiles{record: file.Record{ID: 1, OwnerID: 8}}, fakeObjects{}, fixedClock{}, fixedTokens{}, &fakeCache{})
	_, err := service.Create(context.Background(), 7, 1, nil)
	if !errors.Is(err, file.ErrForbidden) {
		t.Fatalf("expected forbidden, got %v", err)
	}
}

func TestAccessRejectsExpiredShare(t *testing.T) {
	now := time.Date(2026, 8, 16, 12, 0, 0, 0, time.UTC)
	expired := now.Add(-time.Second)
	service := NewService(fakeShares{record: Record{FileID: 1, ExpiresAt: &expired}}, fakeFiles{record: file.Record{ID: 1, OwnerID: 7}}, fakeObjects{}, fixedClock{now}, fixedTokens{}, &fakeCache{})
	_, err := service.Access(context.Background(), "token")
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("expected expired share to be hidden, got %v", err)
	}
}

func TestCreateUsesInjectedClockAndTokenGenerator(t *testing.T) {
	now := time.Date(2026, 8, 16, 12, 0, 0, 0, time.UTC)
	store := fakeShares{}
	service := NewService(store, fakeFiles{record: file.Record{ID: 1, OwnerID: 7}}, fakeObjects{}, fixedClock{now}, fixedTokens{}, &fakeCache{})
	hours := 2
	created, err := service.Create(context.Background(), 7, 1, &hours)
	if err != nil {
		t.Fatal(err)
	}
	if created.Token != "token" || created.ExpiresAt == nil || !created.ExpiresAt.Equal(now.Add(2*time.Hour)) {
		t.Fatalf("created share = %+v", created)
	}
}

func TestAccessUsesCachedShareAndEvictsExpiredEntry(t *testing.T) {
	now := time.Date(2026, 8, 16, 12, 0, 0, 0, time.UTC)
	expires := now.Add(time.Minute)
	cache := &fakeCache{record: Record{FileID: 1, ExpiresAt: &expires}, hit: true}
	service := NewService(fakeShares{findErr: errors.New("database should not be queried")}, fakeFiles{record: file.Record{ID: 1, OwnerID: 7}}, fakeObjects{}, fixedClock{now}, fixedTokens{}, cache)
	if _, err := service.Access(context.Background(), "cached"); err != nil {
		t.Fatal(err)
	}
	expired := now.Add(-time.Second)
	cache.record.ExpiresAt = &expired
	if _, err := service.Access(context.Background(), "cached"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("error = %v", err)
	}
	if cache.deleted != "share:cached" {
		t.Fatalf("deleted key = %q", cache.deleted)
	}
}

func TestRevokeDeletesCachedTokenAfterDatabaseDelete(t *testing.T) {
	cache := &fakeCache{}
	service := NewService(fakeShares{record: Record{ID: 4, OwnerID: 7, Token: "token"}}, fakeFiles{}, fakeObjects{}, fixedClock{}, fixedTokens{}, cache)
	if err := service.Revoke(context.Background(), 7, 4); err != nil {
		t.Fatal(err)
	}
	if cache.deleted != "share:token" {
		t.Fatalf("deleted key = %q", cache.deleted)
	}
}

type fakeShares struct {
	record  Record
	findErr error
}

func (f fakeShares) Create(_ context.Context, ownerID, fileID int64, token string, expires *time.Time) (Record, error) {
	return Record{ID: 1, OwnerID: ownerID, FileID: fileID, Token: token, ExpiresAt: expires}, nil
}
func (f fakeShares) FindByToken(context.Context, string) (Record, error)     { return f.record, f.findErr }
func (f fakeShares) FindOwned(context.Context, int64, int64) (Record, error) { return f.record, nil }
func (f fakeShares) Delete(context.Context, int64) error                     { return nil }

type fakeFiles struct{ record file.Record }

func (f fakeFiles) Find(context.Context, int64) (file.Record, error) { return f.record, nil }

type fakeObjects struct{}

func (fakeObjects) Get(context.Context, string) (io.ReadCloser, error) { return nil, nil }
func (fakeObjects) GetRange(context.Context, string, int64, int64) (io.ReadCloser, error) {
	return nil, nil
}

type fixedClock struct{ now time.Time }

func (f fixedClock) Now() time.Time { return f.now }

type fixedTokens struct{}

func (fixedTokens) Generate(int) (string, error) { return "token", nil }

type fakeCache struct {
	record  Record
	hit     bool
	deleted string
	ttl     time.Duration
}

func (f *fakeCache) Get(context.Context, string) (Record, bool, error) { return f.record, f.hit, nil }
func (f *fakeCache) Set(_ context.Context, _ string, record Record, ttl time.Duration) error {
	f.record, f.hit, f.ttl = record, true, ttl
	return nil
}
func (f *fakeCache) Delete(_ context.Context, key string) error { f.deleted = key; return nil }
