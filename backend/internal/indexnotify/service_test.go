package indexnotify

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"
)

func TestDrainRetriesFailedNotification(t *testing.T) {
	now := time.Date(2026, 8, 16, 12, 0, 0, 0, time.UTC)
	queue := &fakeQueue{tasks: []string{`{"type":"reindex","file_id":7,"owner_id":3}`}}
	service := NewService(queue, fakeSender{}, fixedClock{now})
	service.DrainOnce(context.Background())
	if len(queue.pushed) != 1 {
		t.Fatalf("expected retry task, pushed=%v", queue.pushed)
	}
	var task Task
	if err := json.Unmarshal([]byte(queue.pushed[0]), &task); err != nil {
		t.Fatal(err)
	}
	if task.Attempts != 1 || task.NextRetry != now.Add(2*time.Second).Unix() {
		t.Fatalf("retry task=%+v", task)
	}
}

func TestDrainDropsAfterThirdFailure(t *testing.T) {
	queue := &fakeQueue{tasks: []string{`{"type":"unindex","file_id":7,"owner_id":3,"attempts":2}`}}
	NewService(queue, fakeSender{}, fixedClock{time.Date(2026, 8, 16, 12, 0, 0, 0, time.UTC)}).DrainOnce(context.Background())
	if len(queue.pushed) != 0 {
		t.Fatalf("task should be dropped after max attempts: %v", queue.pushed)
	}
}

func TestQueueFailureFallsBackToAsyncSend(t *testing.T) {
	done := make(chan struct{}, 1)
	service := NewService(failingQueue{}, senderFunc(func(context.Context, string, int64, int64) bool { done <- struct{}{}; return true }), fixedClock{})
	service.Reindex(7, 3)
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("fallback sender was not called")
	}
}

type fakeQueue struct {
	tasks  []string
	pushed []string
}

func (q *fakeQueue) Push(_ context.Context, task string) error {
	q.pushed = append(q.pushed, task)
	return nil
}
func (q *fakeQueue) Pop(_ context.Context) (string, bool, error) {
	if len(q.tasks) == 0 {
		return "", false, nil
	}
	task := q.tasks[0]
	q.tasks = q.tasks[1:]
	return task, true, nil
}

type failingQueue struct{}

func (failingQueue) Push(context.Context, string) error        { return errors.New("redis unavailable") }
func (failingQueue) Pop(context.Context) (string, bool, error) { return "", false, nil }

type fakeSender struct{}

func (fakeSender) Send(context.Context, string, int64, int64) bool { return false }

type senderFunc func(context.Context, string, int64, int64) bool

func (f senderFunc) Send(ctx context.Context, kind string, fileID, ownerID int64) bool {
	return f(ctx, kind, fileID, ownerID)
}

type fixedClock struct{ now time.Time }

func (f fixedClock) Now() time.Time { return f.now }
