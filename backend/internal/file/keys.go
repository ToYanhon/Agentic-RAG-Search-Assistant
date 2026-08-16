package file

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"strconv"
	"time"
)

type RandomKey struct{}

func (RandomKey) NewKey(ownerID int64) string {
	buf := make([]byte, 16)
	_, _ = rand.Read(buf)
	return "users/" + strconv.FormatInt(ownerID, 10) + "/" + strconv.FormatInt(time.Now().UnixNano(), 10) + "-" + hex.EncodeToString(buf)
}

type NoopNotifier struct{}

func (NoopNotifier) Reindex(int64, int64) {}
func (NoopNotifier) Unindex(int64, int64) {}

type NoopProfileCache struct{}

func (NoopProfileCache) Delete(context.Context, int64) error { return nil }

type NoopChecksumCache struct{}

func (NoopChecksumCache) Get(context.Context, int64, string) (bool, bool, error) {
	return false, false, nil
}
func (NoopChecksumCache) Set(context.Context, int64, string, bool, time.Duration) error { return nil }
func (NoopChecksumCache) Delete(context.Context, int64, string) error                   { return nil }
