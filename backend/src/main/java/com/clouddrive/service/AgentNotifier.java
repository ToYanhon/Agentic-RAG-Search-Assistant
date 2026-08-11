package com.clouddrive.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.clouddrive.config.AppProperties;

/**
 * 文件删除后异步通知 Agent 清理 Qdrant 索引（对齐 Go agent_notify，尽力而为不阻断主链路）。
 */
@Component
public class AgentNotifier {

    private static final Logger log = LoggerFactory.getLogger(AgentNotifier.class);

    private final AppProperties props;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "agent-notifier");
        t.setDaemon(true);
        return t;
    });

    public AgentNotifier(AppProperties props) {
        this.props = props;
    }

    public void notifyUnindex(long fileId, long ownerId) {
        String base = props.getAgent().getBaseUrl();
        if (base == null || base.isEmpty()) {
            return;
        }
        executor.execute(() -> {
            try {
                String url = base + "/index/" + fileId;
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header("X-User-Id", String.valueOf(ownerId))
                        .DELETE()
                        .build();
                HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
                if (resp.statusCode() >= 400) {
                    log.warn("agent unindex returned status {} for file {}", resp.statusCode(), fileId);
                }
            } catch (Exception e) {
                log.warn("agent unindex notify failed for file {}", fileId, e);
            }
        });
    }
}
