package llmconfig

import (
	"context"
	"errors"
	"strings"
	"testing"
)

func TestNormalizeBaseURL(t *testing.T) {
	if got := NormalizeBaseURL(" https://api.example.com/v1/chat/completions/?x=1 "); got != "https://api.example.com/v1" {
		t.Fatalf("normalized=%q", got)
	}
}

func TestUpsertPreservesSecretWhenKeyIsEmpty(t *testing.T) {
	repo := &fakeRepository{value: Stored{Provider: "openai", APIKeyEnc: "old"}}
	service := NewService(repo, fakeSecret{})
	if err := service.Upsert(context.Background(), 1, "openai", "https://api.example.com/", "", "model"); err != nil {
		t.Fatal(err)
	}
	if repo.key != "old" || repo.baseURL != "https://api.example.com" {
		t.Fatalf("key=%q url=%q", repo.key, repo.baseURL)
	}
}

func TestUpsertRejectsProviderLongerThan64Characters(t *testing.T) {
	service := NewService(&fakeRepository{}, fakeSecret{})
	if err := service.Upsert(context.Background(), 7, strings.Repeat("a", 65), "", "", ""); !errors.Is(err, ErrProviderTooLong) {
		t.Fatalf("error = %v", err)
	}
}

type fakeRepository struct {
	value        Stored
	key, baseURL string
}

func (f *fakeRepository) Find(context.Context, int64, string) (Stored, error) { return f.value, nil }
func (f *fakeRepository) FindAll(context.Context, int64) ([]Stored, error) {
	return []Stored{f.value}, nil
}
func (f *fakeRepository) Upsert(_ context.Context, _ int64, _ string, base, key, _ string) error {
	f.baseURL = base
	f.key = key
	return nil
}
func (f *fakeRepository) Delete(context.Context, int64, string) error { return nil }

type fakeSecret struct{}

func (fakeSecret) Encrypt(value string) (string, error) { return "enc:" + value, nil }
func (fakeSecret) Decrypt(string) (string, error)       { return "", nil }
