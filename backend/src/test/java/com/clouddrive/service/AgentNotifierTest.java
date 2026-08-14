package com.clouddrive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.clouddrive.config.AppProperties;
import com.clouddrive.security.AgentTokenManager;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * AgentNotifier 队列逻辑测试：入队 / Redis 失败回退直发 / 消费与退避重试。
 * 不触达真实 Redis / agent（全部 mock）。
 */
class AgentNotifierTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private AppProperties props() {
        AppProperties p = new AppProperties();
        p.getAgent().setBaseUrl("http://127.0.0.1:8000");
        return p;
    }

    private AgentTokenManager token() {
        AgentTokenManager t = mock(AgentTokenManager.class);
        when(t.get()).thenReturn("tok");
        return t;
    }

    @SuppressWarnings("unchecked")
    private StringRedisTemplate redis(ListOperations<String, String> ops) {
        StringRedisTemplate r = mock(StringRedisTemplate.class);
        when(r.opsForList()).thenReturn(ops);
        return r;
    }

    @Test
    void enqueuePushesJsonTask() {
        ListOperations<String, String> ops = mock(ListOperations.class);
        AgentNotifier n = new AgentNotifier(props(), token(), redis(ops));

        n.notifyReindex(82L, 42L);

        org.mockito.ArgumentCaptor<String> cap = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(ops).leftPush(anyString(), cap.capture());
        assertThat(cap.getValue()).contains("\"type\":\"reindex\"");
        assertThat(cap.getValue()).contains("\"file_id\":82");
    }

    @Test
    void enqueueFailureFallsBackDirect() {
        // opsForList().leftPush 抛异常 → 回退直发（直发目标 unreachable，不抛）
        ListOperations<String, String> ops = mock(ListOperations.class);
        when(ops.leftPush(anyString(), anyString())).thenThrow(new RuntimeException("redis down"));
        AgentNotifier n = new AgentNotifier(props(), token(), redis(ops));

        // 不应抛异常（回退路径吞掉网络失败）
        n.notifyUnindex(7L, 1L);
    }

    @Test
    void drainEmptyQueueNoOp() {
        ListOperations<String, String> ops = mock(ListOperations.class);
        when(ops.rightPop(anyString())).thenReturn(null);
        AgentNotifier n = new AgentNotifier(props(), token(), redis(ops));

        n.drainQueue();
        // 空队列不产生副作用（无 verify 异常即通过）
    }

    @Test
    void drainRequeuesBeforeRetryTime() {
        ListOperations<String, String> ops = mock(ListOperations.class);
        long future = System.currentTimeMillis() / 1000 + 60;
        String raw = "{\"type\":\"reindex\",\"file_id\":82,\"owner_id\":42,"
                + "\"attempts\":1,\"next_retry\":" + future + "}";
        when(ops.rightPop(anyString())).thenReturn(raw);
        AgentNotifier n = new AgentNotifier(props(), token(), redis(ops));

        n.drainQueue();

        // 未到重试时间 → 放回队尾
        verify(ops).leftPush(anyString(), anyString());
    }

    @Test
    void drainDropsAfterMaxAttempts() {
        ListOperations<String, String> ops = mock(ListOperations.class);
        // attempts=2 已是第 3 次尝试（MAX_ATTEMPTS=3），发送失败即丢弃
        String raw = "{\"type\":\"unindex\",\"file_id\":7,\"owner_id\":1,"
                + "\"attempts\":2,\"next_retry\":0}";
        when(ops.rightPop(anyString())).thenReturn(raw);
        // agent base-url 指向不可达端口，send 失败 → 应丢弃（不重入队）
        AppProperties p = new AppProperties();
        p.getAgent().setBaseUrl("http://127.0.0.1:1"); // 不可达
        AgentNotifier n = new AgentNotifier(p, token(), redis(ops));

        n.drainQueue();

        // 超过尝试次数不再重新入队
        org.mockito.Mockito.verify(ops, org.mockito.Mockito.never())
                .leftPush(anyString(), anyString());
    }

    @Test
    void drainParsesMalformedAndDrops() {
        ListOperations<String, String> ops = mock(ListOperations.class);
        when(ops.rightPop(anyString())).thenReturn("{not-json");
        AgentNotifier n = new AgentNotifier(props(), token(), redis(ops));

        n.drainQueue();
    }

    @Test
    void fallbackHttpClientUsesHttp11() throws Exception {
        // D5：回退直发必须与 AgentClient 一致走 HTTP/1.1（HTTP/2 h2c upgrade 会被 uvicorn 拒收）
        ListOperations<String, String> ops = mock(ListOperations.class);
        AgentNotifier n = new AgentNotifier(props(), token(), redis(ops));

        java.lang.reflect.Field f = AgentNotifier.class.getDeclaredField("http");
        f.setAccessible(true);
        java.net.http.HttpClient client = (java.net.http.HttpClient) f.get(n);
        assertThat(client.version()).isEqualTo(java.net.http.HttpClient.Version.HTTP_1_1);
    }
}
