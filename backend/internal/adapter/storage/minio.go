// Package storage 通过 MinIO 的 S3 兼容 API 实现对象存储端口。
package storage

import (
	"context"
	"io"

	"github.com/clouddrive-ai/backend/internal/multipart"
	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

type MinIO struct {
	client *minio.Client
	core   *minio.Core
	bucket string
}

// CreateMultipart 创建 S3 multipart upload。
func (s *MinIO) CreateMultipart(ctx context.Context, key, contentType string) (string, error) {
	return s.core.NewMultipartUpload(ctx, s.bucket, key, minio.PutObjectOptions{ContentType: contentType})
}

func (s *MinIO) UploadPart(ctx context.Context, key, uploadID string, partNumber int, body io.Reader, size int64) (string, error) {
	part, err := s.core.PutObjectPart(ctx, s.bucket, key, uploadID, partNumber, body, size, minio.PutObjectPartOptions{})
	return part.ETag, err
}

func (s *MinIO) CompleteMultipart(ctx context.Context, key, uploadID string, parts []multipart.Part) error {
	completed := make([]minio.CompletePart, 0, len(parts))
	for _, part := range parts {
		completed = append(completed, minio.CompletePart{PartNumber: part.Number, ETag: part.ETag})
	}
	_, err := s.core.CompleteMultipartUpload(ctx, s.bucket, key, uploadID, completed, minio.PutObjectOptions{})
	return err
}

func (s *MinIO) AbortMultipart(ctx context.Context, key, uploadID string) error {
	return s.core.AbortMultipartUpload(ctx, s.bucket, key, uploadID)
}

func (s *MinIO) IncompleteUploads(ctx context.Context) ([]multipart.IncompleteUpload, error) {
	uploads := make([]multipart.IncompleteUpload, 0)
	for item := range s.client.ListIncompleteUploads(ctx, s.bucket, "users/", true) {
		if item.Err != nil {
			return nil, item.Err
		}
		uploads = append(uploads, multipart.IncompleteUpload{ObjectKey: item.Key, UploadID: item.UploadID})
	}
	return uploads, nil
}

func (s *MinIO) HeadSize(ctx context.Context, key string) (int64, error) {
	info, err := s.client.StatObject(ctx, s.bucket, key, minio.StatObjectOptions{})
	return info.Size, err
}

func New(endpoint, accessKey, secretKey, bucket string) (*MinIO, error) {
	client, err := minio.New(endpoint, &minio.Options{Creds: credentials.NewStaticV4(accessKey, secretKey, ""), Secure: false})
	if err != nil {
		return nil, err
	}
	core, err := minio.NewCore(endpoint, &minio.Options{Creds: credentials.NewStaticV4(accessKey, secretKey, ""), Secure: false})
	if err != nil {
		return nil, err
	}
	return &MinIO{client: client, core: core, bucket: bucket}, nil
}

func (s *MinIO) EnsureBucket(ctx context.Context) error {
	exists, err := s.client.BucketExists(ctx, s.bucket)
	if err != nil || exists {
		return err
	}
	return s.client.MakeBucket(ctx, s.bucket, minio.MakeBucketOptions{})
}

func (s *MinIO) Put(ctx context.Context, key, contentType string, body io.Reader, size int64) error {
	_, err := s.client.PutObject(ctx, s.bucket, key, body, size, minio.PutObjectOptions{ContentType: contentType})
	return err
}

func (s *MinIO) Get(ctx context.Context, key string) (io.ReadCloser, error) {
	object, err := s.client.GetObject(ctx, s.bucket, key, minio.GetObjectOptions{})
	if err != nil {
		return nil, err
	}
	if _, err := object.Stat(); err != nil {
		_ = object.Close()
		return nil, err
	}
	return object, nil
}

func (s *MinIO) GetRange(ctx context.Context, key string, offset, length int64) (io.ReadCloser, error) {
	options := minio.GetObjectOptions{}
	if err := options.SetRange(offset, offset+length-1); err != nil {
		return nil, err
	}
	object, err := s.client.GetObject(ctx, s.bucket, key, options)
	return object, err
}

func (s *MinIO) Delete(ctx context.Context, key string) error {
	return s.client.RemoveObject(ctx, s.bucket, key, minio.RemoveObjectOptions{})
}

func (s *MinIO) Copy(ctx context.Context, sourceKey, destinationKey string) error {
	_, err := s.client.CopyObject(ctx, minio.CopyDestOptions{Bucket: s.bucket, Object: destinationKey}, minio.CopySrcOptions{Bucket: s.bucket, Object: sourceKey})
	return err
}
