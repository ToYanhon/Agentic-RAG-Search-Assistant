package multipart

import (
	"context"
	"log"
	"strings"
	"time"
)

type IncompleteUpload struct {
	ObjectKey string
	UploadID  string
}
type CleanupMetadata interface {
	Exists(context.Context, string) (bool, error)
}
type CleanupStorage interface {
	IncompleteUploads(context.Context) ([]IncompleteUpload, error)
	AbortMultipart(context.Context, string, string) error
}

// CleanupWorker 定期终止 Redis 会话已过期的 users/ multipart upload。
type CleanupWorker struct {
	storage  CleanupStorage
	metadata CleanupMetadata
	interval time.Duration
}

func NewCleanupWorker(storage CleanupStorage, metadata CleanupMetadata) *CleanupWorker {
	return &CleanupWorker{storage: storage, metadata: metadata, interval: 30 * time.Minute}
}
func (w *CleanupWorker) Run(ctx context.Context) {
	timer := time.NewTimer(w.interval)
	defer timer.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-timer.C:
			w.Cleanup(ctx)
			timer.Reset(w.interval)
		}
	}
}
func (w *CleanupWorker) Cleanup(ctx context.Context) {
	uploads, err := w.storage.IncompleteUploads(ctx)
	if err != nil {
		log.Printf("multipart cleanup list failed: %v", err)
		return
	}
	aborted := 0
	for _, upload := range uploads {
		if !strings.HasPrefix(upload.ObjectKey, "users/") {
			continue
		}
		exists, err := w.metadata.Exists(ctx, upload.UploadID)
		if err != nil {
			log.Printf("multipart cleanup metadata check failed upload_id=%s: %v", upload.UploadID, err)
			continue
		}
		if exists {
			continue
		}
		if err := w.storage.AbortMultipart(ctx, upload.ObjectKey, upload.UploadID); err != nil {
			log.Printf("multipart cleanup abort failed upload_id=%s: %v", upload.UploadID, err)
			continue
		}
		aborted++
	}
	if aborted > 0 {
		log.Printf("multipart cleanup aborted %d expired uploads", aborted)
	}
}
