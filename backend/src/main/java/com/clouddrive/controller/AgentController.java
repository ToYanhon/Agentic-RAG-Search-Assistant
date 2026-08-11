package com.clouddrive.controller;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Enumeration;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.clouddrive.common.Resp;
import com.clouddrive.proxy.AgentClient;
import com.clouddrive.service.LLMConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Agent 代理（对齐 Go agent_routes）：全部 /api/v1/agent/* 转发到 Python agent，
 * 注入 X-User-Id / 解密后的 X-LLM-*（+X-Tavily-Key），SSE 逐块透传 + flush，
 * 并发超限 503、传输错误 502。每个 agent 接口必须在此显式注册。
 *
 * 流式实现：直接写 HttpServletResponse 状态/头，返回裸 StreamingResponseBody
 * （ResponseEntity<StreamingResponseBody> 无消息转换器，不能用于流式）。
 */
@RestController
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length",
            "expect"); // expect 是 Java HttpClient 的受限头，复制会抛异常

    private final AgentClient agentClient;
    private final LLMConfigService llmCfg;
    private final ObjectMapper objectMapper;

    public AgentController(AgentClient agentClient, LLMConfigService llmCfg, ObjectMapper objectMapper) {
        this.agentClient = agentClient;
        this.llmCfg = llmCfg;
        this.objectMapper = objectMapper;
    }

    // ---------- 会话 ----------

    @PostMapping("/api/v1/agent/chat/sessions")
    public StreamingResponseBody createSession(HttpServletRequest request, HttpServletResponse response) {
        return proxy(request, response, "/chat/sessions");
    }

    @GetMapping("/api/v1/agent/chat/sessions")
    public StreamingResponseBody listSessions(HttpServletRequest request, HttpServletResponse response) {
        return proxy(request, response, "/chat/sessions");
    }

    @GetMapping("/api/v1/agent/chat/sessions/{session_id}/messages")
    public StreamingResponseBody getMessages(@PathVariable("session_id") String sessionId,
                                             HttpServletRequest request, HttpServletResponse response) {
        return proxy(request, response, "/chat/sessions/" + sessionId + "/messages");
    }

    @PostMapping("/api/v1/agent/chat/sessions/{session_id}/messages")
    public StreamingResponseBody sendMessage(@PathVariable("session_id") String sessionId,
                                             HttpServletRequest request, HttpServletResponse response) {
        return proxy(request, response, "/chat/sessions/" + sessionId + "/messages");
    }

    @PutMapping("/api/v1/agent/chat/sessions/{session_id}")
    public StreamingResponseBody renameSession(@PathVariable("session_id") String sessionId,
                                               HttpServletRequest request, HttpServletResponse response) {
        return proxy(request, response, "/chat/sessions/" + sessionId);
    }

    @DeleteMapping("/api/v1/agent/chat/sessions/{session_id}")
    public StreamingResponseBody deleteSession(@PathVariable("session_id") String sessionId,
                                               HttpServletRequest request, HttpServletResponse response) {
        return proxy(request, response, "/chat/sessions/" + sessionId);
    }

    // ---------- 摘要 / 索引 ----------

    @PostMapping("/api/v1/agent/summary/{file_id}")
    public StreamingResponseBody summary(@PathVariable("file_id") Long fileId,
                                         HttpServletRequest request, HttpServletResponse response) {
        return proxy(request, response, "/summary/" + fileId);
    }

    @PostMapping("/api/v1/agent/index/status")
    public StreamingResponseBody indexStatus(HttpServletRequest request, HttpServletResponse response) {
        return proxy(request, response, "/index/status");
    }

    @PostMapping("/api/v1/agent/index/{file_id}")
    public StreamingResponseBody indexFile(@PathVariable("file_id") Long fileId,
                                           HttpServletRequest request, HttpServletResponse response) {
        return proxy(request, response, "/index/" + fileId);
    }

    @DeleteMapping("/api/v1/agent/index/{file_id}")
    public StreamingResponseBody unindexFile(@PathVariable("file_id") Long fileId,
                                             HttpServletRequest request, HttpServletResponse response) {
        return proxy(request, response, "/index/" + fileId);
    }

    @PostMapping("/api/v1/agent/index/folder/{folder_id}/status")
    public StreamingResponseBody indexFolderStatus(@PathVariable("folder_id") Long folderId,
                                                   HttpServletRequest request, HttpServletResponse response) {
        return proxy(request, response, "/index/folder/" + folderId + "/status");
    }

    @PostMapping("/api/v1/agent/index/folder/{folder_id}")
    public StreamingResponseBody indexFolder(@PathVariable("folder_id") Long folderId,
                                             HttpServletRequest request, HttpServletResponse response) {
        return proxy(request, response, "/index/folder/" + folderId);
    }

    @DeleteMapping("/api/v1/agent/index/folder/{folder_id}")
    public StreamingResponseBody unindexFolder(@PathVariable("folder_id") Long folderId,
                                               HttpServletRequest request, HttpServletResponse response) {
        return proxy(request, response, "/index/folder/" + folderId);
    }

    // ---------- 记忆 ----------

    @GetMapping("/api/v1/agent/memory")
    public StreamingResponseBody listMemory(HttpServletRequest request, HttpServletResponse response) {
        return proxy(request, response, "/memory");
    }

    @DeleteMapping("/api/v1/agent/memory")
    public StreamingResponseBody deleteMemory(HttpServletRequest request, HttpServletResponse response) {
        return proxy(request, response, "/memory");
    }

    // ---------- 代理核心 ----------

    private StreamingResponseBody proxy(HttpServletRequest request, HttpServletResponse response, String path) {
        if (!agentClient.tryAcquire()) {
            writeError(response, 503, "agent busy");
            return null;
        }
        try {
            byte[] body = readBody(request);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(AgentClient.uri(agentClient.baseUrl(), path, request.getQueryString()))
                    .method(request.getMethod(),
                            body == null ? HttpRequest.BodyPublishers.noBody()
                                    : HttpRequest.BodyPublishers.ofByteArray(body));
            copyInboundHeaders(request, builder);
            Long userId = (Long) request.getAttribute("user_id");
            if (userId != null) {
                builder.header("X-User-Id", String.valueOf(userId));
            }
            injectLLMConfig(request, builder, userId);
            String rid = (String) request.getAttribute("request_id");
            if (rid != null) {
                builder.header("X-Request-Id", rid);
            }

            HttpResponse<InputStream> resp = agentClient.send(builder.build());

            if (log.isDebugEnabled()) {
                log.debug("proxy {} -> {} status={}", request.getMethod(), path, resp.statusCode());
            }

            response.setStatus(resp.statusCode());
            resp.headers().map().forEach((name, values) -> {
                String ln = name.toLowerCase();
                if (HOP_BY_HOP.contains(ln) || values.isEmpty()) {
                    return;
                }
                response.setHeader(name, values.get(0));
            });
            return out -> streamAndRelease(resp.body(), out, agentClient);
        } catch (Exception e) {
            agentClient.release();
            log.warn("agent proxy failed: {} {} -> {}", request.getMethod(), path, e.toString());
            writeError(response, 502, "agent unavailable");
            return null;
        }
    }

    private static void streamAndRelease(InputStream in, OutputStream out, AgentClient client) {
        try (InputStream input = in) {
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = input.read(buf)) > 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException e) {
            // 客户端断开/流中断：静默结束
        } finally {
            client.release();
        }
    }

    private void injectLLMConfig(HttpServletRequest request, HttpRequest.Builder builder, Long userId) {
        if (userId == null) {
            return;
        }
        String provider = request.getHeader("X-LLM-Provider");
        if (provider == null || provider.isEmpty()) {
            provider = "openai";
        }
        LLMConfigService.ResolveResult r = llmCfg.resolve(userId, provider);
        if (r.ok()) {
            if (r.baseUrl() != null && !r.baseUrl().isEmpty()) {
                builder.header("X-LLM-Base-URL", r.baseUrl());
            }
            if (r.apiKey() != null && !r.apiKey().isEmpty()) {
                builder.header("X-LLM-Key", r.apiKey());
            }
            if (r.model() != null && !r.model().isEmpty()) {
                builder.header("X-LLM-Model", r.model());
            }
        }
        LLMConfigService.ResolveResult tav = llmCfg.resolve(userId, "tavily");
        if (tav.ok() && tav.apiKey() != null && !tav.apiKey().isEmpty()) {
            builder.header("X-Tavily-Key", tav.apiKey());
        }
    }

    private void copyInboundHeaders(HttpServletRequest request, HttpRequest.Builder builder) {
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            if (HOP_BY_HOP.contains(name.toLowerCase())) {
                continue;
            }
            Enumeration<String> values = request.getHeaders(name);
            while (values.hasMoreElements()) {
                builder.header(name, values.nextElement());
            }
        }
    }

    private byte[] readBody(HttpServletRequest request) throws IOException {
        String method = request.getMethod();
        if ("GET".equals(method) || "DELETE".equals(method) || "HEAD".equals(method)) {
            return null;
        }
        try (InputStream in = request.getInputStream()) {
            byte[] body = in.readNBytes(16 * 1024 * 1024);
            return body.length == 0 ? null : body;
        }
    }

    private void writeError(HttpServletResponse response, int status, String message) {
        try {
            response.setStatus(status);
            response.setContentType("application/json;charset=UTF-8");
            objectMapper.writeValue(response.getOutputStream(), Resp.error(-1, message));
        } catch (IOException e) {
            log.warn("failed to write proxy error response", e);
        }
    }
}
