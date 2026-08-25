package com.clouddrive.agentproxy;

import com.clouddrive.auth.AgentTokenManager;
import com.clouddrive.common.Errors;
import com.clouddrive.llmconfig.LlmConfigService;
import com.clouddrive.llmconfig.Resolved;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Agent 受控 HTTP 转发客户端，对应 Go agentproxy.Client。 HTTP/1.1（uvicorn 不支持 h2c）；并发信号量（满则 ErrBusy
 * -> 503）；header 黑名单过滤 + 可信注入。
 */
@org.springframework.stereotype.Component
public class AgentProxyClient {

	private static final Set<String> BLOCKED = Set.of("connection", "keep-alive", "proxy-authenticate",
			"proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length", "expect",
			"x-user-id", "x-agent-token", "x-llm-provider", "x-llm-base-url", "x-llm-key", "x-llm-model",
			"x-tavily-key");

	private final String baseUrl;

	private final HttpClient client;

	private final Semaphore sem;

	private final AgentTokenManager tokens;

	private final LlmConfigService configs;

	private final Duration headerTimeout;

	public AgentProxyClient(com.clouddrive.config.AppProperties properties, AgentTokenManager tokens,
			LlmConfigService configs) {
		String raw = properties.getAgentBaseUrl();
		this.baseUrl = raw == null ? "" : raw.replaceAll("/+$", "");
		long headerTimeoutSec = properties.getAgentResponseHeaderTimeoutSec();
		this.headerTimeout = headerTimeoutSec <= 0 ? Duration.ofSeconds(60) : Duration.ofSeconds(headerTimeoutSec);
		int maxConcurrent = properties.getAgentMaxConcurrent();
		this.sem = new Semaphore(maxConcurrent <= 0 ? 20 : maxConcurrent);
		this.client = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(10))
			.build();
		this.tokens = tokens;
		this.configs = configs;
	}

	public ProxyResponse forward(String method, String path, String query, byte[] payload, HttpHeaders inbound,
			long userId) {
		if (!sem.tryAcquire()) {
			throw new Errors.AgentBusy("agent busy");
		}
		try {
			String url = baseUrl + path;
			if (query != null && !query.isEmpty()) {
				url += "?" + query;
			}
			HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.method(method, HttpRequest.BodyPublishers.ofByteArray(payload));
			inbound.map().forEach((name, values) -> {
				if (!isBlocked(name)) {
					values.forEach(value -> builder.header(name, value));
				}
			});
			builder.header("X-User-Id", Long.toString(userId));
			String token;
			try {
				token = tokens.current();
			}
			catch (RuntimeException e) {
				token = null;
			}
			if (token != null && !token.isEmpty()) {
				builder.header("X-Agent-Token", token);
			}
			String provider = inbound.firstValue("X-LLM-Provider").orElse("openai");
			builder.header("X-LLM-Provider", provider);
			Resolved llm = configs.resolve(userId, provider);
			if (llm.ok()) {
				setIfPresent(builder, "X-LLM-Base-URL", llm.baseUrl());
				setIfPresent(builder, "X-LLM-Key", llm.apiKey());
				setIfPresent(builder, "X-LLM-Model", llm.model());
			}
			Resolved tavily = configs.resolve(userId, "tavily");
			if (tavily.ok() && tavily.apiKey() != null && !tavily.apiKey().isEmpty()) {
				builder.header("X-Tavily-Key", tavily.apiKey());
			}
			HttpRequest request = builder.build();
			CompletableFuture<HttpResponse<InputStream>> future = client.sendAsync(request,
					HttpResponse.BodyHandlers.ofInputStream());
			HttpResponse<InputStream> response;
			try {
				response = future.get(headerTimeout.toMillis(), TimeUnit.MILLISECONDS);
			}
			catch (TimeoutException e) {
				future.cancel(true);
				throw new Errors.AgentUnavailable("agent unavailable");
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				future.cancel(true);
				throw new Errors.AgentUnavailable("agent unavailable");
			}
			catch (ExecutionException e) {
				throw new Errors.AgentUnavailable("agent unavailable");
			}
			InputStream body = new ReleaseBody(response.body(), sem::release);
			return new ProxyResponse(response.statusCode(), response.headers(), body);
		}
		catch (RuntimeException e) {
			sem.release();
			throw e;
		}
	}

	public static boolean isBlocked(String name) {
		return BLOCKED.contains(name.toLowerCase(Locale.ROOT));
	}

	private static void setIfPresent(HttpRequest.Builder builder, String name, String value) {
		if (value != null && !value.isEmpty()) {
			builder.header(name, value);
		}
	}

	public record ProxyResponse(int status, HttpHeaders headers, InputStream body) {
	}

	private static final class ReleaseBody extends FilterInputStream {

		private final Runnable release;

		private boolean closed;

		ReleaseBody(InputStream in, Runnable release) {
			super(in);
			this.release = release;
		}

		@Override
		public void close() throws IOException {
			if (closed) {
				return;
			}
			closed = true;
			try {
				super.close();
			}
			finally {
				release.run();
			}
		}

	}

}