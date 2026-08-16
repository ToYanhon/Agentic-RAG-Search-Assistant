// Package file 包含与框架无关的对象生命周期用例。
package file

import (
	"bytes"
	"context"
	"crypto/md5"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"strings"
	"time"
)

var (
	ErrNotFound        = errors.New("file not found")
	ErrForbidden       = errors.New("access denied")
	ErrStorageExceeded = errors.New("storage limit exceeded")
	ErrNotTextFile     = errors.New("not a text file")
)

type Record struct {
	ID        int64
	OwnerID   int64
	FolderID  *int64
	Name      string
	Size      int64
	MimeType  string
	MD5       string
	ObjectKey string
	CreatedAt time.Time
}

type Draft struct {
	OwnerID, Size int64
	FolderID      *int64
	Name          string
	MimeType      string
	MD5           string
	ObjectKey     string
}

type Repository interface {
	Find(context.Context, int64) (Record, error)
	FindByMD5Owner(context.Context, int64, string) (Record, error)
	CreateWithQuota(context.Context, Draft) (Record, error)
	UpdateContent(context.Context, Record, int64, string) error
	DeleteWithQuota(context.Context, Record) error
	FilesInFolders(context.Context, []int64) ([]Record, error)
	DeleteFolderCascade(context.Context, int64, []int64, []Record) error
}

// ObjectDeletionQueue 持久化已提交文件对应的对象删除任务。
type ObjectDeletionQueue interface {
	Ensure(context.Context) error
	Pending(context.Context, int) ([]DeletionTask, error)
	Complete(context.Context, int64) error
	Retry(context.Context, int64, int) error
}

type DeletionTask struct {
	ID        int64
	ObjectKey string
}

type FolderTree interface {
	FindFolder(context.Context, int64) (int64, error)
	DescendantIDs(context.Context, int64) ([]int64, error)
}
type ObjectStore interface {
	Put(context.Context, string, string, io.Reader, int64) error
	Get(context.Context, string) (io.ReadCloser, error)
	Delete(context.Context, string) error
	Copy(context.Context, string, string) error
	GetRange(context.Context, string, int64, int64) (io.ReadCloser, error)
}
type KeyGenerator interface{ NewKey(int64) string }
type Notifier interface {
	Reindex(int64, int64)
	Unindex(int64, int64)
}
type ProfileCache interface {
	Delete(context.Context, int64) error
}
type ChecksumCache interface {
	Get(context.Context, int64, string) (bool, bool, error)
	Set(context.Context, int64, string, bool, time.Duration) error
	Delete(context.Context, int64, string) error
}

type Service struct {
	records   Repository
	folders   FolderTree
	quota     QuotaReader
	objects   ObjectStore
	keys      KeyGenerator
	notifier  Notifier
	profiles  ProfileCache
	checksums ChecksumCache
}

func NewService(records Repository, folders FolderTree, quota QuotaReader, objects ObjectStore, keys KeyGenerator, notifier Notifier, profiles ProfileCache, checksums ChecksumCache) *Service {
	return &Service{records: records, folders: folders, quota: quota, objects: objects, keys: keys, notifier: notifier, profiles: profiles, checksums: checksums}
}

func (s *Service) Upload(ctx context.Context, ownerID int64, folderID *int64, name, mime string, data []byte) (Record, error) {
	if name == "" {
		return Record{}, fmt.Errorf("name required")
	}
	if folderID != nil && *folderID != 0 {
		folderOwner, err := s.folders.FindFolder(ctx, *folderID)
		if err != nil {
			return Record{}, err
		}
		if folderOwner != ownerID {
			return Record{}, ErrForbidden
		}
	} else {
		folderID = nil
	}
	remaining, ok, err := s.quotaRemaining(ctx, ownerID)
	if err != nil {
		return Record{}, err
	}
	if !ok || int64(len(data)) > remaining {
		return Record{}, ErrStorageExceeded
	}
	key := s.keys.NewKey(ownerID)
	if err := s.objects.Put(ctx, key, mime, bytes.NewReader(data), int64(len(data))); err != nil {
		return Record{}, err
	}
	digest := md5.Sum(data)
	digestHex := hex.EncodeToString(digest[:])
	record, err := s.records.CreateWithQuota(ctx, Draft{OwnerID: ownerID, FolderID: folderID, Name: name, Size: int64(len(data)), MimeType: mime, MD5: digestHex, ObjectKey: key})
	if err != nil {
		_ = s.objects.Delete(ctx, key)
		return Record{}, err
	}
	s.invalidateProfile(ctx, record.OwnerID)
	s.invalidateChecksum(ctx, record.OwnerID, digestHex)
	s.notifier.Reindex(record.ID, record.OwnerID)
	return record, nil
}

// ChecksumInstant 命中同一 owner 的 MD5 文件后，通过 CopyObject 创建独立对象。
func (s *Service) ChecksumInstant(ctx context.Context, ownerID int64, md5, name string, size int64, folderID *int64) (Record, bool, error) {
	if exists, hit, err := s.checksums.Get(ctx, ownerID, md5); err == nil && hit && !exists {
		return Record{}, false, nil
	}
	source, err := s.records.FindByMD5Owner(ctx, ownerID, md5)
	if errors.Is(err, ErrNotFound) {
		_ = s.checksums.Set(ctx, ownerID, md5, false, time.Minute)
		return Record{}, false, nil
	}
	if err != nil || source.Size != size {
		return Record{}, false, err
	}
	_ = s.checksums.Set(ctx, ownerID, md5, true, time.Minute)
	if folderID != nil && *folderID != 0 {
		folderOwner, err := s.folders.FindFolder(ctx, *folderID)
		if err != nil {
			return Record{}, false, err
		}
		if folderOwner != ownerID {
			return Record{}, false, ErrForbidden
		}
	} else {
		folderID = nil
	}
	remaining, ok, err := s.quotaRemaining(ctx, ownerID)
	if err != nil {
		return Record{}, false, err
	}
	if !ok || size > remaining {
		return Record{}, false, ErrStorageExceeded
	}
	key := s.keys.NewKey(ownerID)
	if err := s.objects.Copy(ctx, source.ObjectKey, key); err != nil {
		return Record{}, false, err
	}
	record, err := s.records.CreateWithQuota(ctx, Draft{OwnerID: ownerID, FolderID: folderID, Name: name, Size: size, MimeType: source.MimeType, MD5: md5, ObjectKey: key})
	if err != nil {
		_ = s.objects.Delete(ctx, key)
		return Record{}, false, err
	}
	s.invalidateProfile(ctx, record.OwnerID)
	s.invalidateChecksum(ctx, record.OwnerID, md5)
	s.notifier.Reindex(record.ID, record.OwnerID)
	return record, true, nil
}

type QuotaReader interface {
	Remaining(context.Context, int64) (int64, bool, error)
}

func (s *Service) quotaRemaining(ctx context.Context, ownerID int64) (int64, bool, error) {
	return s.quota.Remaining(ctx, ownerID)
}

// CreateTextFile 创建文本文件，写入规则与 Agent 工具保持一致。
func (s *Service) CreateTextFile(ctx context.Context, ownerID int64, name, content string, folderID *int64) (Record, error) {
	if name == "" {
		return Record{}, errors.New("name required")
	}
	return s.Upload(ctx, ownerID, folderID, name, "text/plain", []byte(content))
}

func (s *Service) OverwriteContent(ctx context.Context, ownerID, id int64, content string) (Record, error) {
	record, err := s.records.Find(ctx, id)
	if err != nil {
		return Record{}, err
	}
	if record.OwnerID != ownerID {
		return Record{}, ErrForbidden
	}
	if !IsTextFile(record.Name) {
		return Record{}, ErrNotTextFile
	}
	data := []byte(content)
	remaining, ok, err := s.quotaRemaining(ctx, ownerID)
	if err != nil {
		return Record{}, err
	}
	projected := remaining + record.Size
	if !ok || int64(len(data)) > projected {
		return Record{}, ErrStorageExceeded
	}
	key := s.keys.NewKey(ownerID)
	if err := s.objects.Put(ctx, key, "text/plain; charset=utf-8", bytes.NewReader(data), int64(len(data))); err != nil {
		return Record{}, err
	}
	updated := record
	updated.Size = int64(len(data))
	updated.MimeType = "text/plain; charset=utf-8"
	digest := md5.Sum(data)
	updated.MD5 = hex.EncodeToString(digest[:])
	updated.ObjectKey = key
	if err := s.records.UpdateContent(ctx, updated, updated.Size-record.Size, record.ObjectKey); err != nil {
		_ = s.objects.Delete(ctx, key)
		return Record{}, err
	}
	s.invalidateProfile(ctx, updated.OwnerID)
	s.invalidateChecksum(ctx, updated.OwnerID, record.MD5)
	s.invalidateChecksum(ctx, updated.OwnerID, updated.MD5)
	s.notifier.Reindex(updated.ID, updated.OwnerID)
	return updated, nil
}

type ContentView struct {
	Content    string `json:"content"`
	TotalLines int    `json:"total_lines"`
	Truncated  bool   `json:"truncated"`
}

func (s *Service) ReadContent(ctx context.Context, ownerID, id int64, offset int, limit *int) (ContentView, error) {
	record, err := s.records.Find(ctx, id)
	if err != nil {
		return ContentView{}, err
	}
	if record.OwnerID != ownerID {
		return ContentView{}, ErrForbidden
	}
	if !IsTextFile(record.Name) {
		return ContentView{}, ErrNotTextFile
	}
	body, err := s.objects.Get(ctx, record.ObjectKey)
	if err != nil {
		return ContentView{}, err
	}
	defer body.Close()
	data, err := io.ReadAll(body)
	if err != nil {
		return ContentView{}, err
	}
	lines := strings.Split(string(data), "\n")
	from := offset
	if from < 1 {
		from = 1
	}
	if from > len(lines) {
		return ContentView{TotalLines: len(lines)}, nil
	}
	to := len(lines)
	if limit != nil && *limit > 0 && from+*limit-1 < to {
		to = from + *limit - 1
	}
	return ContentView{Content: strings.Join(lines[from-1:to], "\n"), TotalLines: len(lines), Truncated: to < len(lines)}, nil
}

func (s *Service) Download(ctx context.Context, ownerID, id int64) (Record, io.ReadCloser, error) {
	record, err := s.records.Find(ctx, id)
	if err != nil {
		return Record{}, nil, err
	}
	if record.OwnerID != ownerID {
		return Record{}, nil, ErrForbidden
	}
	body, err := s.objects.Get(ctx, record.ObjectKey)
	return record, body, err
}

func (s *Service) DownloadRange(ctx context.Context, ownerID, id, offset, length int64) (Record, io.ReadCloser, error) {
	record, err := s.records.Find(ctx, id)
	if err != nil {
		return Record{}, nil, err
	}
	if record.OwnerID != ownerID {
		return Record{}, nil, ErrForbidden
	}
	body, err := s.objects.GetRange(ctx, record.ObjectKey, offset, length)
	return record, body, err
}

func (s *Service) Delete(ctx context.Context, ownerID, id int64) error {
	record, err := s.records.Find(ctx, id)
	if err != nil {
		return err
	}
	if record.OwnerID != ownerID {
		return ErrForbidden
	}
	if err := s.records.DeleteWithQuota(ctx, record); err != nil {
		return err
	}
	s.invalidateProfile(ctx, record.OwnerID)
	s.invalidateChecksum(ctx, record.OwnerID, record.MD5)
	s.notifier.Unindex(record.ID, record.OwnerID)
	return nil
}

// DeleteFolder 删除用户拥有的文件夹子树及其包含的全部文件。
func (s *Service) DeleteFolder(ctx context.Context, ownerID, folderID int64) error {
	folderOwner, err := s.folders.FindFolder(ctx, folderID)
	if err != nil {
		return err
	}
	if folderOwner != ownerID {
		return ErrForbidden
	}
	folderIDs, err := s.folders.DescendantIDs(ctx, folderID)
	if err != nil {
		return err
	}
	files, err := s.records.FilesInFolders(ctx, folderIDs)
	if err != nil {
		return err
	}
	if err := s.records.DeleteFolderCascade(ctx, ownerID, folderIDs, files); err != nil {
		return err
	}
	if len(files) > 0 {
		s.invalidateProfile(ctx, ownerID)
		for _, record := range files {
			s.invalidateChecksum(ctx, record.OwnerID, record.MD5)
		}
	}
	for _, record := range files {
		s.notifier.Unindex(record.ID, record.OwnerID)
	}
	return nil
}

func (s *Service) invalidateProfile(ctx context.Context, ownerID int64) {
	if s.profiles != nil {
		_ = s.profiles.Delete(ctx, ownerID)
	}
}

func (s *Service) invalidateChecksum(ctx context.Context, ownerID int64, md5 string) {
	if s.checksums != nil && md5 != "" {
		_ = s.checksums.Delete(ctx, ownerID, md5)
	}
}
