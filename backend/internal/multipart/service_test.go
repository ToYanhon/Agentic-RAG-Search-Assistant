package multipart

import (
	"context"
	"errors"
	"io"
	"testing"
	"time"

	"github.com/clouddrive-ai/backend/internal/file"
)

func TestCompleteRejectsExceededQuotaBeforeObjectMerge(t *testing.T) {
	meta := Meta{OwnerID: 7, Size: 10, TotalChunks: 1, ObjectKey: "users/7/object", UploadID: "upload"}
	objects := &completeObjects{}
	service := NewService(completeMetadata{meta: meta}, objects, nil, nil, nil, fixedQuota{remaining: 9}, nil, nil)
	_, err := service.Complete(context.Background(), "upload", 7)
	if !errors.Is(err, file.ErrStorageExceeded) {
		t.Fatalf("error = %v", err)
	}
	if objects.completes != 0 {
		t.Fatal("object must not be merged when complete quota precheck fails")
	}
}

func TestCompleteInvalidatesProfileCacheAfterQuotaChanges(t *testing.T) {
	meta := Meta{OwnerID: 7, Size: 10, TotalChunks: 1, ObjectKey: "users/7/object", UploadID: "upload"}
	profiles := &completeProfiles{}
	service := NewService(completeMetadata{meta: meta}, successfulObjects{}, nil, nil, completeRecords{}, fixedQuota{remaining: 10}, noopNotifier{}, profiles)
	if _, err := service.Complete(context.Background(), "upload", 7); err != nil {
		t.Fatal(err)
	}
	if profiles.deleted != 7 {
		t.Fatalf("profile cache invalidated for user %d", profiles.deleted)
	}
}

type completeMetadata struct{ meta Meta }

func (m completeMetadata) Get(context.Context, string) (Meta, error)             { return m.meta, nil }
func (completeMetadata) Save(context.Context, string, Meta, time.Duration) error { return nil }
func (completeMetadata) SavePart(context.Context, string, int, string, time.Duration) error {
	return nil
}
func (completeMetadata) ReceivedParts(context.Context, string) ([]int, error)  { return []int{0}, nil }
func (completeMetadata) PartETag(context.Context, string, int) (string, error) { return "etag", nil }
func (completeMetadata) Delete(context.Context, string) error                  { return nil }

type completeObjects struct{ completes int }

func (*completeObjects) CreateMultipart(context.Context, string, string) (string, error) {
	return "", nil
}
func (*completeObjects) UploadPart(context.Context, string, string, int, io.Reader, int64) (string, error) {
	return "", nil
}
func (o *completeObjects) CompleteMultipart(context.Context, string, string, []Part) error {
	o.completes++
	return nil
}
func (*completeObjects) AbortMultipart(context.Context, string, string) error { return nil }
func (*completeObjects) HeadSize(context.Context, string) (int64, error)      { return 0, nil }
func (*completeObjects) Delete(context.Context, string) error                 { return nil }

type fixedQuota struct{ remaining int64 }

func (q fixedQuota) Remaining(context.Context, int64) (int64, bool, error) {
	return q.remaining, true, nil
}

type successfulObjects struct{}

func (successfulObjects) CreateMultipart(context.Context, string, string) (string, error) {
	return "", nil
}
func (successfulObjects) UploadPart(context.Context, string, string, int, io.Reader, int64) (string, error) {
	return "", nil
}
func (successfulObjects) CompleteMultipart(context.Context, string, string, []Part) error { return nil }
func (successfulObjects) AbortMultipart(context.Context, string, string) error            { return nil }
func (successfulObjects) HeadSize(context.Context, string) (int64, error)                 { return 10, nil }
func (successfulObjects) Delete(context.Context, string) error                            { return nil }

type completeRecords struct{}

func (completeRecords) CreateWithQuota(context.Context, file.Draft) (file.Record, error) {
	return file.Record{ID: 1, OwnerID: 7}, nil
}

type noopNotifier struct{}

func (noopNotifier) Reindex(int64, int64) {}

type completeProfiles struct{ deleted int64 }

func (p *completeProfiles) Delete(_ context.Context, ownerID int64) error {
	p.deleted = ownerID
	return nil
}
