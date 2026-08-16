package config_test

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/clouddrive-ai/backend/internal/config"
	. "github.com/clouddrive-ai/backend/internal/config"
)

// withDotEnv 写入临时 .env 并加载，测试结束后恢复进程环境，避免测试之间相互污染。
func withDotEnv(t *testing.T, content string) {
	t.Helper()
	path := filepath.Join(t.TempDir(), ".env")
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	before := snapshotEnv(t)
	t.Cleanup(before)
	if err := LoadDotEnv(path); err != nil {
		t.Fatalf("LoadDotEnv() error = %v", err)
	}
}

func snapshotEnv(t *testing.T) func() {
	t.Helper()
	before := map[string]string{}
	for _, kv := range os.Environ() {
		key, value, ok := strings.Cut(kv, "=")
		if ok {
			before[key] = value
		}
	}
	return func() {
		for _, kv := range os.Environ() {
			key, _, _ := strings.Cut(kv, "=")
			if _, existed := before[key]; !existed {
				os.Unsetenv(key)
			}
		}
		for key, value := range before {
			os.Setenv(key, value)
		}
	}
}

func TestLoadDotEnvPopulatesConfig(t *testing.T) {
	withDotEnv(t, "CD_JWT_SECRET=file-secret\nCD_SERVER_PORT=9999\nCD_MINIO_BUCKET=file-bucket\n")
	cfg := FromEnv()
	if cfg.JWTSecret != "file-secret" {
		t.Errorf("JWTSecret = %q, want file-secret", cfg.JWTSecret)
	}
	if cfg.ServerPort != 9999 {
		t.Errorf("ServerPort = %d, want 9999", cfg.ServerPort)
	}
	if cfg.MinIOBucket != "file-bucket" {
		t.Errorf("MinIOBucket = %q, want file-bucket", cfg.MinIOBucket)
	}
}

func TestLoadDotEnvKeepsExistingEnv(t *testing.T) {
	t.Setenv("CD_JWT_SECRET", "real-secret")
	t.Setenv("CD_SERVER_PORT", "1234")
	withDotEnv(t, "CD_JWT_SECRET=file-secret\nCD_SERVER_PORT=9999\nCD_MINIO_BUCKET=file-bucket\n")
	cfg := FromEnv()
	if cfg.JWTSecret != "real-secret" {
		t.Errorf("JWTSecret = %q, want real-secret（真实环境变量优先）", cfg.JWTSecret)
	}
	if cfg.ServerPort != 1234 {
		t.Errorf("ServerPort = %d, want 1234（真实环境变量优先）", cfg.ServerPort)
	}
	if cfg.MinIOBucket != "file-bucket" {
		t.Errorf("MinIOBucket = %q, want file-bucket（新变量由文件注入）", cfg.MinIOBucket)
	}
}

func TestLoadDotEnvIgnoresMissingFile(t *testing.T) {
	if err := LoadDotEnv(filepath.Join(t.TempDir(), "not-exists.env")); err != nil {
		t.Fatalf("LoadDotEnv() with missing file error = %v, want nil", err)
	}
}

func TestLoadDotEnvDefaultsRemain(t *testing.T) {
	withDotEnv(t, "CD_SERVER_PORT=8081\n")
	cfg := FromEnv()
	if cfg.ServerPort != 8081 {
		t.Errorf("ServerPort = %d, want 8081", cfg.ServerPort)
	}
	if cfg.RedisAddr != "localhost:6379" {
		t.Errorf("RedisAddr = %q, want default localhost:6379", cfg.RedisAddr)
	}
}

func TestFromEnvDefaultsWithoutEnv(t *testing.T) {
	t.Setenv("CD_SERVER_PORT", "")
	cfg := config.FromEnv()
	if cfg.ServerPort != 8080 {
		t.Errorf("ServerPort = %d, want 8080", cfg.ServerPort)
	}
}
