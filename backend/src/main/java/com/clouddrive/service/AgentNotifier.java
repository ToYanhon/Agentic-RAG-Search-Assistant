package com.clouddrive.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.clouddrive.config.AppProperties;
import com.clouddrive.security.AgentTokenManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 文件变更后通知 Agent 维护 Qdrant 索引（对齐 Go agent_notify，尽力而为不阻断主链路）。
 *
 * 索引通知经 Redis 队列（task:index_notify）持久化 + 轮询消费 + 指数退避重试；
 * Redis 不可用或入队失败时回退直发（单线程 executor 异步 HTTP，仍尽力而为）。
 * 删除 → unindex；覆盖写 → reindex。携带 X-Agent-Token 供 agent 侧未来收紧校验。
 */
@Component
public class AgentNotifier {

    private static final Logger log = LoggerFactory.getLogger(AgentNotifier.class);

    private static final String QUEUE_KEY = "task:index_notify";
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BASE_SEC = 2;
    private static final long RETRY_CAP_SEC = 30;

    private final AppProperties props;
    private final AgentTokenManager tokenManager;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "agent-notifier-fallback");
        t.setDaemon(true);
        return t;
    });

    public AgentNotifier(AppProperties props, AgentTokenManager tokenManager,
                         StringRedisTemplate redis) {
        this.props = props;
        this.tokenManager = tokenManager;
        this.redis = redis;
    }

    public void notifyUnindex(long fileId, long ownerId) {
        notify("unindex", fileId, ownerId);
    }

    public void notifyReindex(long fileId, long ownerId) {
        notify("reindex", fileId, ownerId);
    }

    // ---------- 生产者 ----------

    private void notify(String type, long fileId, long ownerId) {
        Map<String, Object> task = Map.of(
                "type", type,
                "file_id", fileId,
                "owner_id", ownerId,
                "attempts", 0,
                "next_retry", 0L);
        try {
            redis.opsForList().leftPush(QUEUE_KEY, objectMapper.writeValueAsString(task));
        } catch (Exception e) {
            log.warn("index notify enqueue failed for file {} (type {}), fallback direct: {}",
                    fileId, type, e.toString());
            // 回退直发：异步执行，不阻塞业务线程/事务
            executor.execute(() -> send(type, fileId, ownerId));
        }
    }

    // ---------- 消费者（轮询） ----------

    @Scheduled(fixedDelay = 2000)
    public void drainQueue() {
        try {
            String raw = redis.opsForList().rightPop(QUEUE_KEY);
            if (raw == null) {
                return;
            }
            Map<String, Object> task;
            try {
                task = objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception e) {
                log.warn("index notify task malformed, dropped: {}", raw);
                return;
            }
            String type = String.valueOf(task.get("type"));
            long fileId = ((Number) task.get("file_id")).longValue();
            long ownerId = ((Number) task.get("owner_id")).longValue();
            int attempts = ((Number) task.getOrDefault("attempts", 0)).intValue();
            long nextRetry = ((Number) task.getOrDefault("next_retry", 0L)).longValue();

            if (nextRetry > System.currentTimeMillis() / 1000) {
                // 未到重试时间，放回队尾
                try {
                    redis.opsForList().leftPush(QUEUE_KEY, raw);
                } catch (Exception e) {
                    log.warn("index notify re-enqueue failed for file {}: {}", fileId, e.toString());
                }
                return;
            }
            if (send(type, fileId, ownerId)) {
                return;
            }
            // 发送失败：尝试次数内按指数退避重新入队，否则丢弃并告警
            if (attempts + 1 >= MAX_ATTEMPTS) {
                log.warn("index notify dropped after {} attempts for file {} (type {})",
                        attempts + 1, fileId, type);
                return;
            }
            long delay = Math.min(RETRY_BASE_SEC * (1L << attempts), RETRY_CAP_SEC);
            task.put("attempts", attempts + 1);
            task.put("next_retry", System.currentTimeMillis() / 1000 + delay);
            try {
                redis.opsForList().leftPush(QUEUE_KEY,
                        objectMapper.writeValueAsString(task));
                log.info("index notify scheduled retry for file {} (type {}, attempt {})",
                        fileId, type, attempts + 1);
            } catch (Exception e) {
                log.warn("index notify re-enqueue failed for file {}: {}", fileId, e.toString());
            }
        } catch (Exception e) {
            log.warn("index notify drain failed", e);
        }
    }

    // ---------- 直发（回退 / 消费执行共用） ----------

    /** 直发 agent 通知；成功返回 true。 */
    private boolean send(String type, long fileId, long ownerId) {
        String base = props.getAgent().getBaseUrl();
        if (base == null || base.isEmpty()) {
            return true;
        }
        try {
            String token = tokenManager.get();
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + "/index/" + fileId))
                    .timeout(Duration.ofSeconds(10))
                    .header("X-User-Id", String.valueOf(ownerId));
            if (token != null && !token.isEmpty()) {
                builder.header("X-Agent-Token", token);
            }
            HttpRequest req = "reindex".equals(type)
                    ? builder.POST(HttpRequest.BodyPublishers.noBody()).build()
                    : builder.DELETE().build();
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() >= 400) {
                log.warn("agent notify {} returned status {} for file {}",
                        type, resp.statusCode(), fileId);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("agent notify {} failed for file {}: {}", type, fileId, e.toString());
            return false;
        }
    }
}
