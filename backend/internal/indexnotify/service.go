// Package indexnotify 编排文件索引变更通知，不依赖 Redis 或 HTTP 实现。
package indexnotify

import (
	"context"
	"encoding/json"
	"log"
	"time"
)

const maxAttempts = 3

type Queue interface {
	Push(context.Context, string) error
	Pop(context.Context) (string, bool, error)
}
type Sender interface {
	Send(context.Context, string, int64, int64) bool
}
type Clock interface{ Now() time.Time }
type Task struct {
	Type      string `json:"type"`
	FileID    int64  `json:"file_id"`
	OwnerID   int64  `json:"owner_id"`
	Attempts  int    `json:"attempts"`
	NextRetry int64  `json:"next_retry"`
}
type Service struct {
	queue  Queue
	sender Sender
	clock  Clock
}

func NewService(queue Queue, sender Sender, clock Clock) *Service {
	return &Service{queue: queue, sender: sender, clock: clock}
}
func (s *Service) Reindex(fileID, ownerID int64) { s.notify("reindex", fileID, ownerID) }
func (s *Service) Unindex(fileID, ownerID int64) { s.notify("unindex", fileID, ownerID) }
func (s *Service) notify(kind string, fileID, ownerID int64) {
	raw, err := json.Marshal(Task{Type: kind, FileID: fileID, OwnerID: ownerID})
	if err == nil && s.queue.Push(context.Background(), string(raw)) == nil {
		return
	}
	go s.send(kind, fileID, ownerID)
}

// Run 每 2 秒最多处理一个任务，避免通知请求突发压垮 Agent。
func (s *Service) Run(ctx context.Context) {
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()
	for {
		s.DrainOnce(ctx)
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}
func (s *Service) DrainOnce(ctx context.Context) {
	raw, ok, err := s.queue.Pop(ctx)
	if err != nil || !ok {
		return
	}
	var task Task
	if json.Unmarshal([]byte(raw), &task) != nil || task.FileID <= 0 || task.OwnerID <= 0 || (task.Type != "reindex" && task.Type != "unindex") {
		return
	}
	if task.NextRetry > s.clock.Now().Unix() {
		_ = s.queue.Push(ctx, raw)
		return
	}
	if s.send(task.Type, task.FileID, task.OwnerID) {
		return
	}
	if task.Attempts+1 >= maxAttempts {
		log.Printf("index notify dropped after %d attempts for file %d type=%s", task.Attempts+1, task.FileID, task.Type)
		return
	}
	task.Attempts++
	task.NextRetry = s.clock.Now().Unix() + min(2*(1<<uint(task.Attempts-1)), 30)
	if next, err := json.Marshal(task); err == nil {
		_ = s.queue.Push(ctx, string(next))
	}
}
func (s *Service) send(kind string, fileID, ownerID int64) bool {
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	return s.sender.Send(ctx, kind, fileID, ownerID)
}
func min(a, b int64) int64 {
	if a < b {
		return a
	}
	return b
}
