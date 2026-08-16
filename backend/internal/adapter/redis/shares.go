package redis

import (
	"context"
	"encoding/json"
	"time"

	"github.com/clouddrive-ai/backend/internal/share"
	redisv9 "github.com/redis/go-redis/v9"
)

// ShareCache 使用独立 key，保证缓存值格式由 Go adapter 自己管理。
type ShareCache struct{ client redisv9.Cmdable }

func NewShareCache(client redisv9.Cmdable) ShareCache { return ShareCache{client: client} }
func (c ShareCache) Get(ctx context.Context, key string) (share.Record, bool, error) {
	value, err := c.client.Get(ctx, "go:"+key).Result()
	if err == redisv9.Nil {
		return share.Record{}, false, nil
	}
	if err != nil {
		return share.Record{}, false, err
	}
	var record share.Record
	if err := json.Unmarshal([]byte(value), &record); err != nil {
		return share.Record{}, false, err
	}
	return record, true, nil
}
func (c ShareCache) Set(ctx context.Context, key string, record share.Record, ttl time.Duration) error {
	value, err := json.Marshal(record)
	if err != nil {
		return err
	}
	return c.client.Set(ctx, "go:"+key, value, ttl).Err()
}
func (c ShareCache) Delete(ctx context.Context, key string) error {
	return c.client.Del(ctx, "go:"+key).Err()
}
