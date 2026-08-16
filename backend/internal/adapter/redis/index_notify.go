package redis

import (
	"context"

	redisv9 "github.com/redis/go-redis/v9"
)

const indexNotifyQueue = "task:index_notify"

// IndexNotifyQueue 使用 LPUSH/RPOP 保持任务先进先出。
type IndexNotifyQueue struct{ client redisv9.Cmdable }

func NewIndexNotifyQueue(client redisv9.Cmdable) IndexNotifyQueue {
	return IndexNotifyQueue{client: client}
}
func (q IndexNotifyQueue) Push(ctx context.Context, task string) error {
	return q.client.LPush(ctx, indexNotifyQueue, task).Err()
}
func (q IndexNotifyQueue) Pop(ctx context.Context) (string, bool, error) {
	task, err := q.client.RPop(ctx, indexNotifyQueue).Result()
	if err == redisv9.Nil {
		return "", false, nil
	}
	return task, err == nil, err
}
