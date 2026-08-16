// Package redis implements Redis infrastructure adapters for identity ports.
package redis

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/clouddrive-ai/backend/internal/auth"
	"github.com/redis/go-redis/v9"
)

type Blacklist struct{ client redis.Cmdable }

func NewBlacklist(client redis.Cmdable) Blacklist { return Blacklist{client: client} }
func (b Blacklist) Add(ctx context.Context, jti string, ttl time.Duration) error {
	return b.client.Set(ctx, "jti_blacklist:"+jti, "1", ttl).Err()
}
func (b Blacklist) Contains(ctx context.Context, jti string) (bool, error) {
	count, err := b.client.Exists(ctx, "jti_blacklist:"+jti).Result()
	return count > 0, err
}

// ProfileCache 使用独立 key，避免不同序列化格式互相污染。
type ProfileCache struct{ client redis.Cmdable }

func NewProfileCache(client redis.Cmdable) ProfileCache { return ProfileCache{client: client} }
func (c ProfileCache) Get(ctx context.Context, userID int64) (auth.Profile, bool, error) {
	value, err := c.client.Get(ctx, profileKey(userID)).Result()
	if err == redis.Nil {
		return auth.Profile{}, false, nil
	}
	if err != nil {
		return auth.Profile{}, false, err
	}
	var profile auth.Profile
	if err := json.Unmarshal([]byte(value), &profile); err != nil {
		return auth.Profile{}, false, err
	}
	return profile, true, nil
}
func (c ProfileCache) Set(ctx context.Context, profile auth.Profile, ttl time.Duration) error {
	value, err := json.Marshal(profile)
	if err != nil {
		return err
	}
	return c.client.Set(ctx, profileKey(profile.ID), value, ttl).Err()
}
func (c ProfileCache) Delete(ctx context.Context, userID int64) error {
	return c.client.Del(ctx, profileKey(userID)).Err()
}
func profileKey(userID int64) string { return "go:user_profile:" + fmt.Sprint(userID) }

type AgentTokenStore struct{ client redis.Cmdable }

func NewAgentTokenStore(client redis.Cmdable) AgentTokenStore { return AgentTokenStore{client: client} }
func (s AgentTokenStore) Save(ctx context.Context, token string, ttl time.Duration) error {
	return s.client.Set(ctx, "internal:agent:token", token, ttl).Err()
}
func (s AgentTokenStore) Get(ctx context.Context) (string, error) {
	return s.client.Get(ctx, "internal:agent:token").Result()
}
