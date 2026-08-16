package redis

import (
	"context"
	"strconv"
	"time"

	redisv9 "github.com/redis/go-redis/v9"
)

// ChecksumCache 使用独立 key，保证缓存值格式由 Go adapter 自己管理。
type ChecksumCache struct{ client redisv9.Cmdable }

func NewChecksumCache(client redisv9.Cmdable) ChecksumCache { return ChecksumCache{client: client} }
func (c ChecksumCache) Get(ctx context.Context, ownerID int64, md5 string) (bool, bool, error) {
	value, err := c.client.Get(ctx, checksumKey(ownerID, md5)).Result()
	if err == redisv9.Nil {
		return false, false, nil
	}
	if err != nil {
		return false, false, err
	}
	exists, err := strconv.ParseBool(value)
	return exists, err == nil, err
}
func (c ChecksumCache) Set(ctx context.Context, ownerID int64, md5 string, exists bool, ttl time.Duration) error {
	return c.client.Set(ctx, checksumKey(ownerID, md5), strconv.FormatBool(exists), ttl).Err()
}
func (c ChecksumCache) Delete(ctx context.Context, ownerID int64, md5 string) error {
	return c.client.Del(ctx, checksumKey(ownerID, md5)).Err()
}
func checksumKey(ownerID int64, md5 string) string {
	return "go:checksum:" + strconv.FormatInt(ownerID, 10) + ":" + md5
}
