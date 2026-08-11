package com.clouddrive.proxy;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.stereotype.Component;

import com.clouddrive.config.AppProperties;

/**
 * Agent（Python）HTTP 客户端（对齐 Go agent_client）：
 * - 不设请求级总超时（避免掐断 SSE 长流），响应头超时由 sendAsync().get(timeout) 承担
 * - 连接超时 10s；并发信号量（max_concurrent，满返回 false → 调用方 503）
 */
@Component
public class AgentClient {

    private final String baseUrl;
    private final long headerTimeoutSec;
    private final Semaphore sem;
    private final HttpClient http;

    public AgentClient(AppProperties props) {
        AppProperties.Agent a = props.getAgent();
        this.baseUrl = a.getBaseUrl();
        this.headerTimeoutSec = a.getResponseHeaderTimeoutSec() > 0 ? a.getResponseHeaderTimeoutSec() : 60;
        int maxConcurrent = a.getMaxConcurrent() > 0 ? a.getMaxConcurrent() : 20;
        this.sem = new Semaphore(maxConcurrent);
        this.http = HttpClient.newBuilder()
                // 强制 HTTP/1.1：默认 HTTP/2 会对明文连接发起 h2c upgrade（uvicorn 不支持会拒收）
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    public boolean tryAcquire() {
        return sem.tryAcquire();
    }

    public void release() {
        sem.release();
    }

    /** 发送请求；等待响应头最多 headerTimeoutSec，随后 body 流式（无总超时）。 */
    public HttpResponse<InputStream> send(HttpRequest req) throws Exception {
        CompletableFuture<HttpResponse<InputStream>> future =
                http.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream());
        try {
            return future.get(headerTimeoutSec, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

    public static URI uri(String baseUrl, String path, String query) {
        String url = baseUrl + path;
        if (query != null && !query.isEmpty()) {
            url += "?" + query;
        }
        return URI.create(url);
    }
}
