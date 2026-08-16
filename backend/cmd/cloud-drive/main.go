// cloud-drive starts the Go backend.
package main

import (
	"context"
	"database/sql"
	"log"
	"net/http"
	"strconv"
	"time"

	adapteragent "github.com/clouddrive-ai/backend/internal/adapter/agent"
	adapterredis "github.com/clouddrive-ai/backend/internal/adapter/redis"
	"github.com/clouddrive-ai/backend/internal/adapter/security"
	adapterstorage "github.com/clouddrive-ai/backend/internal/adapter/storage"
	"github.com/clouddrive-ai/backend/internal/agentproxy"
	"github.com/clouddrive-ai/backend/internal/auth"
	"github.com/clouddrive-ai/backend/internal/catalog"
	"github.com/clouddrive-ai/backend/internal/config"
	"github.com/clouddrive-ai/backend/internal/db"
	fileservice "github.com/clouddrive-ai/backend/internal/file"
	"github.com/clouddrive-ai/backend/internal/httpapi"
	"github.com/clouddrive-ai/backend/internal/indexnotify"
	"github.com/clouddrive-ai/backend/internal/llmconfig"
	"github.com/clouddrive-ai/backend/internal/multipart"
	"github.com/clouddrive-ai/backend/internal/share"
	_ "github.com/go-sql-driver/mysql"
	"github.com/redis/go-redis/v9"
)

func main() {
	settings := config.FromEnv()
	database, err := sql.Open("mysql", settings.MySQLDSN)
	if err != nil {
		log.Fatal(err)
	}
	defer database.Close()
	redisClient := redis.NewClient(&redis.Options{Addr: settings.RedisAddr, Password: settings.RedisPassword})
	defer redisClient.Close()
	random := security.CryptoRandom{}
	tokens := security.NewHS256JWT(settings.JWTSecret, time.Duration(settings.JWTExpireHours)*time.Hour, random)
	service := auth.NewService(db.NewUserRepository(database), tokens, adapterredis.NewBlacklist(redisClient), adapterredis.NewProfileCache(redisClient), security.BCryptHasher{})
	catalogQuery := db.NewCatalogQuery(database)
	catalogService := catalog.NewServiceWithMutations(catalogQuery, catalogQuery, catalogQuery, catalogQuery)
	objects, err := adapterstorage.New(settings.MinIOEndpoint, settings.MinIOAccessKey, settings.MinIOSecretKey, settings.MinIOBucket)
	if err != nil {
		log.Fatal(err)
	}
	if err := objects.EnsureBucket(context.Background()); err != nil {
		log.Fatal(err)
	}
	agents := auth.NewAgentTokenManager(adapterredis.NewAgentTokenStore(redisClient), random)
	if err := agents.Rotate(context.Background()); err != nil {
		log.Printf("agent token rotation failed: %v", err)
	}
	go agents.Run(context.Background())
	fileRepository := db.NewFileRepository(database)
	if err := fileRepository.Ensure(context.Background()); err != nil {
		log.Fatal(err)
	}
	go fileservice.NewDeletionWorker(fileRepository, objects.Delete).Run(context.Background())
	indexQueue := adapterredis.NewIndexNotifyQueue(redisClient)
	notifier := indexnotify.NewService(indexQueue, adapteragent.NewIndexSender(settings.AgentBaseURL, agents), security.SystemClock{})
	go notifier.Run(context.Background())
	profileCache := adapterredis.NewProfileCache(redisClient)
	fileService := fileservice.NewService(fileRepository, fileRepository, fileRepository, objects, fileservice.RandomKey{}, notifier, profileCache, adapterredis.NewChecksumCache(redisClient))
	multipartService := multipart.NewService(
		adapterredis.NewMultipartMetadata(redisClient),
		objects,
		fileRepository,
		fileservice.RandomKey{},
		fileRepository,
		db.NewUserRepository(database),
		notifier,
		profileCache,
	)
	go multipart.NewCleanupWorker(objects, adapterredis.NewMultipartMetadata(redisClient)).Run(context.Background())
	shareService := share.NewService(db.NewShareRepository(database), fileRepository, objects, security.SystemClock{}, random, adapterredis.NewShareCache(redisClient))
	secrets, err := security.NewAesGCMSecret(settings.LLMEncryptionKey)
	if err != nil {
		log.Fatal(err)
	}
	llmService := llmconfig.NewService(db.NewLLMConfigRepository(database), secrets)
	proxyClient := agentproxy.New(agentproxy.Config{BaseURL: settings.AgentBaseURL, HeaderTimeout: time.Duration(settings.AgentHeaderTimeoutSec) * time.Second, MaxConcurrent: settings.AgentMaxConcurrent}, agents, llmService)
	server := &http.Server{Addr: ":" + strconv.Itoa(settings.ServerPort), Handler: httpapi.NewWithProxy(service, agents, catalogService, fileService, multipartService, shareService, llmService, proxyClient, settings.DirectMaxBytes, settings.ChunkMaxBytes, settings.FileMaxBytes), ReadHeaderTimeout: 5 * time.Second}
	log.Printf("Go M1 authentication slice listening on %s", server.Addr)
	log.Fatal(server.ListenAndServe())
}
