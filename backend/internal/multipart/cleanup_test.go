package multipart

import (
	"context"
	"testing"
)

func TestCleanupAbortsOnlyExpiredUserUploads(t *testing.T) {
	storage := &fakeCleanupStorage{uploads: []IncompleteUpload{{ObjectKey: "users/7/a", UploadID: "expired"}, {ObjectKey: "users/7/b", UploadID: "active"}, {ObjectKey: "system/c", UploadID: "other"}}}
	worker := NewCleanupWorker(storage, fakeCleanupMetadata{active: "active"})
	worker.Cleanup(context.Background())
	if len(storage.aborted) != 1 || storage.aborted[0] != "expired" {
		t.Fatalf("aborted=%v", storage.aborted)
	}
}

type fakeCleanupStorage struct {
	uploads []IncompleteUpload
	aborted []string
}

func (f *fakeCleanupStorage) IncompleteUploads(context.Context) ([]IncompleteUpload, error) {
	return f.uploads, nil
}
func (f *fakeCleanupStorage) AbortMultipart(_ context.Context, _ string, id string) error {
	f.aborted = append(f.aborted, id)
	return nil
}

type fakeCleanupMetadata struct{ active string }

func (f fakeCleanupMetadata) Exists(_ context.Context, id string) (bool, error) {
	return id == f.active, nil
}
