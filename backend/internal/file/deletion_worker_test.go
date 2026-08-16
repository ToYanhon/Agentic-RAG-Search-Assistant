package file

import (
	"context"
	"errors"
	"testing"
)

func TestDeletionWorkerCompletesSuccessfulTask(t *testing.T) {
	queue := &fakeDeletionQueue{tasks: []DeletionTask{{ID: 4, ObjectKey: "users/7/file"}}}
	deleted := ""
	worker := NewDeletionWorker(queue, func(_ context.Context, key string) error {
		deleted = key
		return nil
	})
	worker.drain(context.Background())
	if deleted != "users/7/file" || queue.completed != 4 || queue.retried != 0 {
		t.Fatalf("unexpected worker state: deleted=%q completed=%d retried=%d", deleted, queue.completed, queue.retried)
	}
}

func TestDeletionWorkerRetainsFailedTaskForRetry(t *testing.T) {
	queue := &fakeDeletionQueue{tasks: []DeletionTask{{ID: 5, ObjectKey: "users/7/file"}}}
	worker := NewDeletionWorker(queue, func(context.Context, string) error { return errors.New("minio unavailable") })
	worker.drain(context.Background())
	if queue.completed != 0 || queue.retried != 5 {
		t.Fatalf("failed deletion must be retried: completed=%d retried=%d", queue.completed, queue.retried)
	}
}

type fakeDeletionQueue struct {
	tasks     []DeletionTask
	completed int64
	retried   int64
}

func (q *fakeDeletionQueue) Ensure(context.Context) error { return nil }
func (q *fakeDeletionQueue) Pending(context.Context, int) ([]DeletionTask, error) {
	return q.tasks, nil
}
func (q *fakeDeletionQueue) Complete(_ context.Context, id int64) error { q.completed = id; return nil }
func (q *fakeDeletionQueue) Retry(_ context.Context, id int64, _ int) error {
	q.retried = id
	return nil
}
