package file

import (
	"bytes"
	"context"
	"errors"
	"io"
	"testing"
)

func TestUploadDeletesObjectWhenRecordCreationFails(t *testing.T) {
	objects := &fakeObjects{}
	service := NewService(&fakeRecords{createErr: ErrStorageExceeded}, fakeFolders{}, fakeQuota{}, objects, fixedKey("users/7/test"), NoopNotifier{}, NoopProfileCache{}, NoopChecksumCache{})
	_, err := service.Upload(context.Background(), 7, nil, "note.txt", "text/plain", []byte("hello"))
	if !errors.Is(err, ErrStorageExceeded) {
		t.Fatalf("expected quota error, got %v", err)
	}
	if objects.deleted != "users/7/test" {
		t.Fatalf("expected uploaded object cleanup, got %q", objects.deleted)
	}
}

func TestUploadRejectsForeignFolderBeforeWritingObject(t *testing.T) {
	objects := &fakeObjects{}
	service := NewService(&fakeRecords{}, fakeFolders{owner: 8}, fakeQuota{}, objects, fixedKey("key"), NoopNotifier{}, NoopProfileCache{}, NoopChecksumCache{})
	folderID := int64(3)
	_, err := service.Upload(context.Background(), 7, &folderID, "note.txt", "text/plain", []byte("hello"))
	if !errors.Is(err, ErrForbidden) {
		t.Fatalf("expected forbidden, got %v", err)
	}
	if objects.puts != 0 {
		t.Fatal("object must not be written before folder owner validation")
	}
}

func TestUploadRejectsExceededQuotaBeforeWritingObject(t *testing.T) {
	objects := &fakeObjects{}
	service := NewService(&fakeRecords{}, fakeFolders{}, limitedQuota{remaining: 4}, objects, fixedKey("key"), NoopNotifier{}, NoopProfileCache{}, NoopChecksumCache{})
	_, err := service.Upload(context.Background(), 7, nil, "note.txt", "text/plain", []byte("hello"))
	if !errors.Is(err, ErrStorageExceeded) {
		t.Fatalf("expected quota error, got %v", err)
	}
	if objects.puts != 0 {
		t.Fatal("object must not be written when quota precheck fails")
	}
}

func TestUploadInvalidatesProfileCacheAfterQuotaChanges(t *testing.T) {
	profiles := &fakeProfiles{}
	service := NewService(&fakeRecords{}, fakeFolders{}, fakeQuota{}, &fakeObjects{}, fixedKey("key"), NoopNotifier{}, profiles, NoopChecksumCache{})
	if _, err := service.Upload(context.Background(), 7, nil, "note.txt", "text/plain", []byte("hello")); err != nil {
		t.Fatal(err)
	}
	if profiles.deleted != 7 {
		t.Fatalf("profile cache invalidated for user %d", profiles.deleted)
	}
}

func TestDeleteChecksOwnerBeforeDeletingObject(t *testing.T) {
	objects := &fakeObjects{}
	service := NewService(&fakeRecords{record: Record{ID: 1, OwnerID: 8, ObjectKey: "key"}}, fakeFolders{}, fakeQuota{}, objects, fixedKey("key"), NoopNotifier{}, NoopProfileCache{}, NoopChecksumCache{})
	err := service.Delete(context.Background(), 7, 1)
	if !errors.Is(err, ErrForbidden) {
		t.Fatalf("expected forbidden, got %v", err)
	}
	if objects.deleted != "" {
		t.Fatal("foreign file object must not be deleted")
	}
}

func TestDeleteFolderDeletesObjectsThenNotifies(t *testing.T) {
	objects := &fakeObjects{}
	notifier := &fakeNotifier{}
	records := &fakeRecords{folderFiles: []Record{{ID: 1, OwnerID: 7, ObjectKey: "one"}, {ID: 2, OwnerID: 7, ObjectKey: "two"}}}
	service := NewService(records, fakeFolders{owner: 7, descendants: []int64{3, 4}}, fakeQuota{}, objects, fixedKey("key"), notifier, NoopProfileCache{}, NoopChecksumCache{})
	if err := service.DeleteFolder(context.Background(), 7, 3); err != nil {
		t.Fatalf("delete folder: %v", err)
	}
	if records.deleteFolderCalls != 1 || notifier.unindexed != 2 {
		t.Fatalf("expected cascade mutation and two notifications, calls=%d notifications=%d", records.deleteFolderCalls, notifier.unindexed)
	}
}

func TestChecksumInstantCopiesOnlyOwnedMatchingFile(t *testing.T) {
	records := &fakeRecords{record: Record{ID: 1, OwnerID: 7, Size: 5, MimeType: "text/plain", ObjectKey: "source"}}
	objects := &fakeObjects{}
	service := NewService(records, fakeFolders{}, fakeQuota{}, objects, fixedKey("copy"), NoopNotifier{}, NoopProfileCache{}, NoopChecksumCache{})
	record, instant, err := service.ChecksumInstant(context.Background(), 7, "abc", "copy.txt", 5, nil)
	if err != nil || !instant || record.OwnerID != 7 || objects.copiedFrom != "source" || objects.copiedTo != "copy" {
		t.Fatalf("instant upload failed: record=%+v instant=%t err=%v copy=%q->%q", record, instant, err, objects.copiedFrom, objects.copiedTo)
	}
}

func TestReadContentSlicesLinesAndRejectsBinary(t *testing.T) {
	records := &fakeRecords{record: Record{ID: 1, OwnerID: 7, Name: "note.txt", ObjectKey: "key"}}
	objects := &fakeObjects{content: []byte("one\ntwo\nthree")}
	service := NewService(records, fakeFolders{}, fakeQuota{}, objects, fixedKey("key"), NoopNotifier{}, NoopProfileCache{}, NoopChecksumCache{})
	limit := 1
	view, err := service.ReadContent(context.Background(), 7, 1, 2, &limit)
	if err != nil || view.Content != "two" || view.TotalLines != 3 || !view.Truncated {
		t.Fatalf("content view=%+v err=%v", view, err)
	}
	records.record.Name = "archive.zip"
	_, err = service.ReadContent(context.Background(), 7, 1, 1, nil)
	if !errors.Is(err, ErrNotTextFile) {
		t.Fatalf("expected text error, got %v", err)
	}
}

type fakeRecords struct {
	record            Record
	createErr         error
	folderFiles       []Record
	deleteFolderCalls int
}

func (f *fakeRecords) Find(context.Context, int64) (Record, error) { return f.record, nil }
func (f *fakeRecords) FindByMD5Owner(context.Context, int64, string) (Record, error) {
	return f.record, nil
}
func (f *fakeRecords) CreateWithQuota(_ context.Context, draft Draft) (Record, error) {
	if f.createErr != nil {
		return Record{}, f.createErr
	}
	return Record{ID: 1, OwnerID: draft.OwnerID}, nil
}
func (f *fakeRecords) UpdateContent(context.Context, Record, int64, string) error { return nil }
func (f *fakeRecords) DeleteWithQuota(context.Context, Record) error              { return nil }
func (f *fakeRecords) FilesInFolders(context.Context, []int64) ([]Record, error) {
	return f.folderFiles, nil
}
func (f *fakeRecords) DeleteFolderCascade(context.Context, int64, []int64, []Record) error {
	f.deleteFolderCalls++
	return nil
}

type fakeFolders struct {
	owner       int64
	descendants []int64
}

func (f fakeFolders) FindFolder(context.Context, int64) (int64, error) { return f.owner, nil }
func (f fakeFolders) DescendantIDs(context.Context, int64) ([]int64, error) {
	return f.descendants, nil
}

type fakeObjects struct {
	puts       int
	deleted    string
	content    []byte
	copiedFrom string
	copiedTo   string
}

func (f *fakeObjects) Put(_ context.Context, _ string, _ string, _ io.Reader, _ int64) error {
	f.puts++
	return nil
}
func (f *fakeObjects) Get(context.Context, string) (io.ReadCloser, error) {
	return io.NopCloser(bytes.NewReader(f.content)), nil
}
func (f *fakeObjects) GetRange(_ context.Context, _ string, offset, length int64) (io.ReadCloser, error) {
	return io.NopCloser(bytes.NewReader(f.content[offset : offset+length])), nil
}
func (f *fakeObjects) Delete(_ context.Context, key string) error { f.deleted = key; return nil }
func (f *fakeObjects) Copy(_ context.Context, source, destination string) error {
	f.copiedFrom, f.copiedTo = source, destination
	return nil
}

type fixedKey string

func (k fixedKey) NewKey(int64) string { return string(k) }

type fakeNotifier struct{ unindexed int }

func (f *fakeNotifier) Reindex(int64, int64) {}
func (f *fakeNotifier) Unindex(int64, int64) { f.unindexed++ }

type fakeQuota struct{}

func (fakeQuota) Remaining(context.Context, int64) (int64, bool, error) {
	return 1 << 30, true, nil
}

type limitedQuota struct{ remaining int64 }

func (q limitedQuota) Remaining(context.Context, int64) (int64, bool, error) {
	return q.remaining, true, nil
}

type fakeProfiles struct{ deleted int64 }

func (f *fakeProfiles) Delete(_ context.Context, ownerID int64) error {
	f.deleted = ownerID
	return nil
}
