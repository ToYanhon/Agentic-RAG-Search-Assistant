package multipart

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"time"

	"github.com/clouddrive-ai/backend/internal/file"
)

var (
	ErrNotFound     = errors.New("upload not found")
	ErrIncomplete   = errors.New("incomplete parts")
	ErrSizeMismatch = errors.New("uploaded bytes mismatch declared size")
)

type Meta struct {
	OwnerID     int64
	Name        string
	Size        int64
	MimeType    string
	FolderID    *int64
	MD5         string
	ChunkSize   int64
	TotalChunks int
	ObjectKey   string
	UploadID    string
	Remaining   int64
}

type Metadata interface {
	Get(context.Context, string) (Meta, error)
	Save(context.Context, string, Meta, time.Duration) error
	SavePart(context.Context, string, int, string, time.Duration) error
	ReceivedParts(context.Context, string) ([]int, error)
	PartETag(context.Context, string, int) (string, error)
	Delete(context.Context, string) error
}

type ObjectStore interface {
	CreateMultipart(context.Context, string, string) (string, error)
	UploadPart(context.Context, string, string, int, io.Reader, int64) (string, error)
	CompleteMultipart(context.Context, string, string, []Part) error
	AbortMultipart(context.Context, string, string) error
	HeadSize(context.Context, string) (int64, error)
	Delete(context.Context, string) error
}

type Part struct {
	Number int
	ETag   string
}
type FolderOwner interface {
	FindFolder(context.Context, int64) (int64, error)
}
type KeyGenerator interface{ NewKey(int64) string }
type Notifier interface{ Reindex(int64, int64) }
type ProfileCache interface {
	Delete(context.Context, int64) error
}
type Records interface {
	CreateWithQuota(context.Context, file.Draft) (file.Record, error)
}
type Quota interface {
	Remaining(context.Context, int64) (int64, bool, error)
}

type Service struct {
	metadata Metadata
	objects  ObjectStore
	folders  FolderOwner
	keys     KeyGenerator
	records  Records
	quota    Quota
	notifier Notifier
	profiles ProfileCache
	ttl      time.Duration
}

func NewService(metadata Metadata, objects ObjectStore, folders FolderOwner, keys KeyGenerator, records Records, quota Quota, notifier Notifier, profiles ProfileCache) *Service {
	return &Service{metadata: metadata, objects: objects, folders: folders, keys: keys, records: records, quota: quota, notifier: notifier, profiles: profiles, ttl: 24 * time.Hour}
}

func (s *Service) Init(ctx context.Context, ownerID int64, name, mime string, size int64, folderID *int64, md5 string, chunkSize int64) (Meta, error) {
	if folderID != nil && *folderID != 0 {
		folderOwner, err := s.folders.FindFolder(ctx, *folderID)
		if err != nil {
			return Meta{}, err
		}
		if folderOwner != ownerID {
			return Meta{}, file.ErrForbidden
		}
	} else {
		folderID = nil
	}
	total, err := TotalChunks(size, chunkSize)
	if err != nil {
		return Meta{}, err
	}
	remaining, ok, err := s.quota.Remaining(ctx, ownerID)
	if err != nil {
		return Meta{}, err
	}
	if !ok || size > remaining {
		return Meta{}, file.ErrStorageExceeded
	}
	key := s.keys.NewKey(ownerID)
	uploadID, err := s.objects.CreateMultipart(ctx, key, mime)
	if err != nil {
		return Meta{}, err
	}
	meta := Meta{OwnerID: ownerID, Name: name, Size: size, MimeType: mime, FolderID: folderID, MD5: md5, ChunkSize: chunkSize, TotalChunks: total, ObjectKey: key, UploadID: uploadID, Remaining: remaining}
	if err := s.metadata.Save(ctx, uploadID, meta, s.ttl); err != nil {
		_ = s.objects.AbortMultipart(ctx, key, uploadID)
		return Meta{}, err
	}
	return meta, nil
}

func (s *Service) UploadPart(ctx context.Context, uploadID string, ownerID int64, index int, body io.Reader, size int64) ([]int, error) {
	meta, err := s.metadata.Get(ctx, uploadID)
	if err != nil {
		return nil, err
	}
	if meta.OwnerID != ownerID {
		return nil, file.ErrForbidden
	}
	if !ValidIndex(index, meta.TotalChunks) {
		return nil, errors.New("part index out of range")
	}
	if !ValidPartSize(index, meta.TotalChunks, size) {
		return nil, ErrPartTooSmall
	}
	etag, err := s.objects.UploadPart(ctx, meta.ObjectKey, meta.UploadID, index+1, body, size)
	if err != nil {
		return nil, err
	}
	if err := s.metadata.SavePart(ctx, uploadID, index, etag, s.ttl); err != nil {
		return nil, err
	}
	return s.metadata.ReceivedParts(ctx, uploadID)
}

func (s *Service) Complete(ctx context.Context, uploadID string, ownerID int64) (file.Record, error) {
	meta, err := s.metadata.Get(ctx, uploadID)
	if err != nil {
		return file.Record{}, err
	}
	if meta.OwnerID != ownerID {
		return file.Record{}, file.ErrForbidden
	}
	parts, err := s.metadata.ReceivedParts(ctx, uploadID)
	if err != nil {
		return file.Record{}, err
	}
	if !Contiguous(parts, meta.TotalChunks) {
		return file.Record{}, fmt.Errorf("%w: %d/%d", ErrIncomplete, len(parts), meta.TotalChunks)
	}
	remaining, ok, err := s.quota.Remaining(ctx, ownerID)
	if err != nil {
		return file.Record{}, err
	}
	if !ok || meta.Size > remaining {
		return file.Record{}, file.ErrStorageExceeded
	}
	completeParts := make([]Part, 0, meta.TotalChunks)
	for i := 0; i < meta.TotalChunks; i++ {
		etag, err := s.metadata.PartETag(ctx, uploadID, i)
		if err != nil || etag == "" {
			return file.Record{}, fmt.Errorf("missing etag for part %d", i)
		}
		completeParts = append(completeParts, Part{Number: i + 1, ETag: etag})
	}
	if err := s.objects.CompleteMultipart(ctx, meta.ObjectKey, meta.UploadID, completeParts); err != nil {
		log.Printf("multipart complete object failed upload_id=%s: %v", uploadID, err)
		_ = s.objects.AbortMultipart(ctx, meta.ObjectKey, meta.UploadID)
		_ = s.metadata.Delete(ctx, uploadID)
		return file.Record{}, err
	}
	actual, err := s.objects.HeadSize(ctx, meta.ObjectKey)
	if err != nil || actual != meta.Size {
		if err != nil {
			log.Printf("multipart head object failed upload_id=%s: %v", uploadID, err)
		}
		_ = s.objects.Delete(ctx, meta.ObjectKey)
		_ = s.metadata.Delete(ctx, uploadID)
		if err != nil {
			return file.Record{}, err
		}
		return file.Record{}, ErrSizeMismatch
	}
	record, err := s.records.CreateWithQuota(ctx, file.Draft{OwnerID: ownerID, FolderID: meta.FolderID, Name: meta.Name, Size: meta.Size, MimeType: meta.MimeType, MD5: meta.MD5, ObjectKey: meta.ObjectKey})
	if err != nil {
		_ = s.objects.Delete(ctx, meta.ObjectKey)
		_ = s.metadata.Delete(ctx, uploadID)
		return file.Record{}, err
	}
	_ = s.metadata.Delete(ctx, uploadID)
	if s.profiles != nil {
		_ = s.profiles.Delete(ctx, record.OwnerID)
	}
	s.notifier.Reindex(record.ID, record.OwnerID)
	return record, nil
}

func (s *Service) Abort(ctx context.Context, uploadID string, ownerID int64) error {
	meta, err := s.metadata.Get(ctx, uploadID)
	if err != nil {
		return err
	}
	if meta.OwnerID != ownerID {
		return file.ErrForbidden
	}
	if err := s.objects.AbortMultipart(ctx, meta.ObjectKey, meta.UploadID); err != nil {
		return err
	}
	return s.metadata.Delete(ctx, uploadID)
}
