// Package config reads the minimum M1 configuration from the existing CD_* contract.
package config

import (
	"errors"
	"os"
	"strconv"

	"github.com/joho/godotenv"
)

type Config struct {
	ServerPort            int
	MySQLDSN              string
	RedisAddr             string
	RedisPassword         string
	JWTSecret             string
	JWTExpireHours        int
	MinIOEndpoint         string
	MinIOAccessKey        string
	MinIOSecretKey        string
	MinIOBucket           string
	DirectMaxBytes        int64
	AgentBaseURL          string
	AgentHeaderTimeoutSec int
	AgentMaxConcurrent    int
	LLMEncryptionKey      string
	ChunkMaxBytes         int64
	FileMaxBytes          int64
}

// LoadDotEnv 读取 .env 文件并写入进程环境变量，供 FromEnv 使用。
// 语义与 python-dotenv 对齐：真实环境变量优先，已存在的值不会被文件覆盖；
// 文件不存在或为空时静默忽略，不视为错误。
func LoadDotEnv(paths ...string) error {
	for _, path := range paths {
		if err := godotenv.Load(path); err != nil && !errors.Is(err, os.ErrNotExist) {
			return err
		}
	}
	return nil
}

func FromEnv() Config {
	return Config{
		ServerPort:            integer("CD_SERVER_PORT", 8080),
		MySQLDSN:              value("CD_MYSQL_DSN", "root:root@tcp(localhost:3306)/cloud_drive?parseTime=true&loc=Asia%2FShanghai"),
		RedisAddr:             value("CD_REDIS_ADDR", "localhost:6379"),
		RedisPassword:         os.Getenv("CD_REDIS_PASSWORD"),
		JWTSecret:             value("CD_JWT_SECRET", "change-me-in-production"),
		JWTExpireHours:        integer("CD_JWT_EXPIRE_HOURS", 72),
		MinIOEndpoint:         value("CD_MINIO_ENDPOINT", "localhost:9100"),
		MinIOAccessKey:        value("CD_MINIO_ACCESS_KEY", "minioadmin"),
		MinIOSecretKey:        value("CD_MINIO_SECRET_KEY", "minioadmin"),
		MinIOBucket:           value("CD_MINIO_BUCKET", "cloud-drive"),
		DirectMaxBytes:        int64(integer("CD_UPLOAD_DIRECT_MAX_BYTES", 52_428_800)),
		AgentBaseURL:          value("CD_AGENT_BASE_URL", "http://127.0.0.1:8000"),
		AgentHeaderTimeoutSec: integer("CD_AGENT_RESPONSE_HEADER_TIMEOUT_SEC", 60),
		AgentMaxConcurrent:    integer("CD_AGENT_MAX_CONCURRENT", 20),
		LLMEncryptionKey:      value("CD_LLM_ENCRYPTION_KEY", "dev-only-llm-encryption-key-change-me-32b"),
		ChunkMaxBytes:         int64(integer("CD_UPLOAD_CHUNK_MAX_BYTES", 10_485_760)),
		FileMaxBytes:          int64(integer("CD_UPLOAD_FILE_MAX_BYTES", 10_737_418_240)),
	}
}

func value(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}

func integer(key string, fallback int) int {
	value, err := strconv.Atoi(os.Getenv(key))
	if err != nil || value <= 0 {
		return fallback
	}
	return value
}
