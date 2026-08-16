package file

import (
	"context"
	"log"
	"time"
)

// DeletionWorker 在数据库事务提交后异步清理对象，任务本身持久化在 MySQL。
type DeletionWorker struct {
	queue  ObjectDeletionQueue
	delete func(context.Context, string) error
}

func NewDeletionWorker(queue ObjectDeletionQueue, deleteObject func(context.Context, string) error) *DeletionWorker {
	return &DeletionWorker{queue: queue, delete: deleteObject}
}

func (w *DeletionWorker) Run(ctx context.Context) {
	if err := w.queue.Ensure(ctx); err != nil {
		log.Printf("object deletion outbox unavailable: %v", err)
		return
	}
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()
	for {
		w.drain(ctx)
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

func (w *DeletionWorker) drain(ctx context.Context) {
	tasks, err := w.queue.Pending(ctx, 20)
	if err != nil {
		log.Printf("object deletion outbox read failed: %v", err)
		return
	}
	for _, task := range tasks {
		if err := w.delete(ctx, task.ObjectKey); err != nil {
			if retryErr := w.queue.Retry(ctx, task.ID, 1); retryErr != nil {
				log.Printf("object deletion retry update failed for task %d: %v", task.ID, retryErr)
			}
			continue
		}
		if err := w.queue.Complete(ctx, task.ID); err != nil {
			log.Printf("object deletion task completion failed for task %d: %v", task.ID, err)
		}
	}
}
