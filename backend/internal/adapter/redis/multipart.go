package redis

import (
	"context"
	"strconv"
	"time"

	"github.com/clouddrive-ai/backend/internal/multipart"
	redisv9 "github.com/redis/go-redis/v9"
)

type MultipartMetadata struct{ client redisv9.Cmdable }

func NewMultipartMetadata(client redisv9.Cmdable) MultipartMetadata {
	return MultipartMetadata{client: client}
}

func (m MultipartMetadata) Get(ctx context.Context, id string) (multipart.Meta, error) {
	values, err := m.client.HGetAll(ctx, "multipart:"+id).Result()
	if err != nil {
		return multipart.Meta{}, err
	}
	if len(values) == 0 {
		return multipart.Meta{}, multipart.ErrNotFound
	}
	owner, _ := strconv.ParseInt(values["owner_id"], 10, 64)
	size, _ := strconv.ParseInt(values["size"], 10, 64)
	chunk, _ := strconv.ParseInt(values["chunk_size"], 10, 64)
	total, _ := strconv.Atoi(values["total_chunks"])
	var folder *int64
	if id, _ := strconv.ParseInt(values["folder_id"], 10, 64); id > 0 {
		folder = &id
	}
	return multipart.Meta{OwnerID: owner, Name: values["name"], Size: size, MimeType: values["mime_type"], FolderID: folder, MD5: values["md5"], ChunkSize: chunk, TotalChunks: total, ObjectKey: values["object_key"], UploadID: values["upload_id"]}, nil
}

func (m MultipartMetadata) Save(ctx context.Context, id string, meta multipart.Meta, ttl time.Duration) error {
	folder := "0"
	if meta.FolderID != nil {
		folder = strconv.FormatInt(*meta.FolderID, 10)
	}
	values := map[string]any{"owner_id": meta.OwnerID, "name": meta.Name, "size": meta.Size, "mime_type": meta.MimeType, "folder_id": folder, "md5": meta.MD5, "chunk_size": meta.ChunkSize, "total_chunks": meta.TotalChunks, "object_key": meta.ObjectKey, "upload_id": meta.UploadID}
	pipe := m.client.TxPipeline()
	pipe.HSet(ctx, "multipart:"+id, values)
	pipe.Expire(ctx, "multipart:"+id, ttl)
	_, err := pipe.Exec(ctx)
	return err
}

func (m MultipartMetadata) SavePart(ctx context.Context, id string, index int, etag string, ttl time.Duration) error {
	pipe := m.client.TxPipeline()
	pipe.HSet(ctx, "multipart:"+id+":parts", strconv.Itoa(index), etag)
	pipe.Expire(ctx, "multipart:"+id+":parts", ttl)
	pipe.Expire(ctx, "multipart:"+id, ttl)
	_, err := pipe.Exec(ctx)
	return err
}

func (m MultipartMetadata) ReceivedParts(ctx context.Context, id string) ([]int, error) {
	values, err := m.client.HKeys(ctx, "multipart:"+id+":parts").Result()
	if err != nil {
		return nil, err
	}
	parts := make([]int, 0, len(values))
	for _, value := range values {
		index, err := strconv.Atoi(value)
		if err != nil {
			continue
		}
		parts = append(parts, index)
	}
	for i := 1; i < len(parts); i++ {
		for j := i; j > 0 && parts[j] < parts[j-1]; j-- {
			parts[j], parts[j-1] = parts[j-1], parts[j]
		}
	}
	return parts, nil
}

func (m MultipartMetadata) PartETag(ctx context.Context, id string, index int) (string, error) {
	return m.client.HGet(ctx, "multipart:"+id+":parts", strconv.Itoa(index)).Result()
}

func (m MultipartMetadata) Delete(ctx context.Context, id string) error {
	return m.client.Del(ctx, "multipart:"+id, "multipart:"+id+":parts").Err()
}

func (m MultipartMetadata) Exists(ctx context.Context, id string) (bool, error) {
	count, err := m.client.Exists(ctx, "multipart:"+id).Result()
	return count > 0, err
}
