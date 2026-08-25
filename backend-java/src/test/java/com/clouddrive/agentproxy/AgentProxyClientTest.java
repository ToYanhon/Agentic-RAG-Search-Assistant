package com.clouddrive.agentproxy;

import com.clouddrive.auth.AgentTokenManager;
import com.clouddrive.auth.AgentTokenStore;
import com.clouddrive.auth.RandomHex;
import com.clouddrive.common.Errors;
import com.clouddrive.config.AppProperties;
import com.clouddrive.llmconfig.LlmConfigService;
import com.clouddrive.llmconfig.Repository;
import com.clouddrive.llmconfig.Secret;
import com.clouddrive.llmconfig.Stored;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentProxyClientTest {

	private HttpServer server;

	private final List<String> receivedXUserId = new ArrayList<>();

	private final List<String> receivedXAgentToken = new ArrayList<>();

	private final List<String> receivedProvider = new ArrayList<>();

	private final List<String> receivedLLMKey = new ArrayList<>();

	private final List<String> receivedCustom = new ArrayList<>();

	private volatile CountDownLatch blockLatch;

	private volatile int responseStatus = HttpURLConnection.HTTP_OK;

	@BeforeEach
	void setUp() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/chat/sessions", new HttpHandler() {
			@Override
			public void handle(HttpExchange exchange) throws IOException {
				receivedXUserId.add(first(exchange, "X-User-Id"));
				receivedXAgentToken.add(first(exchange, "X-Agent-Token"));
				receivedProvider.add(first(exchange, "X-LLM-Provider"));
				receivedLLMKey.add(first(exchange, "X-LLM-Key"));
				receivedCustom.add(first(exchange, "X-Custom"));
				if (blockLatch != null) {
					try {
						if (!blockLatch.await(5, TimeUnit.SECONDS)) {
							exchange.close();
							return;
						}
					}
					catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
				byte[] body = "hello agent".getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(responseStatus, body.length);
				exchange.getResponseBody().write(body);
				exchange.close();
			}
		});
		server.start();
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	private static String first(HttpExchange exchange, String name) {
		List<String> values = exchange.getRequestHeaders().get(name);
		return values == null || values.isEmpty() ? null : values.get(0);
	}

	private AgentProxyClient client(int maxConcurrent) {
		AppProperties properties = new AppProperties();
		properties.setAgentBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
		properties.setAgentResponseHeaderTimeoutSec(2);
		properties.setAgentMaxConcurrent(maxConcurrent);
		AgentTokenManager tokens = new AgentTokenManager(new FakeAgentTokenStore(), new FakeRandomHex());
		LlmConfigService configs = new LlmConfigService(new FakeLlmConfigRepository(), new FakeSecret());
		return new AgentProxyClient(properties, tokens, configs);
	}

	@Test
	void forwardInjectsTrustedHeadersAndDropsForgedOnes() throws IOException {
		AgentProxyClient client = client(5);
		Map<String, List<String>> headers = Map.of("X-User-Id", List.of("999"), "X-Agent-Token", List.of("forged"),
				"X-LLM-Provider", List.of("anthropic"), "X-Custom", List.of("pass-through"));
		java.net.http.HttpHeaders inbound = java.net.http.HttpHeaders.of(headers, (n, v) -> true);
		byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
		AgentProxyClient.ProxyResponse response = client.forward("POST", "/chat/sessions", "", payload, inbound, 7);
		byte[] body = response.body().readAllBytes();
		assertEquals("hello agent", new String(body, StandardCharsets.UTF_8));
		assertEquals("7", receivedXUserId.get(0));
		assertEquals("agent-token-123", receivedXAgentToken.get(0));
		assertEquals("anthropic", receivedProvider.get(0));
		assertEquals("sk-real-key", receivedLLMKey.get(0));
		assertEquals("pass-through", receivedCustom.get(0));
	}

	@Test
	void forwardDefaultsProviderToOpenai() throws IOException {
		AgentProxyClient client = client(5);
		java.net.http.HttpHeaders inbound = java.net.http.HttpHeaders.of(Map.of(), (n, v) -> true);
		client.forward("POST", "/chat/sessions", "", new byte[0], inbound, 7).body().close();
		assertEquals("openai", receivedProvider.get(0));
	}

	@Test
	void forwardBusyWhenConcurrencyExhausted() throws Exception {
		AgentProxyClient client = client(1);
		blockLatch = new CountDownLatch(1);
		java.net.http.HttpHeaders inbound = java.net.http.HttpHeaders.of(Map.of(), (n, v) -> true);
		var thread = new Thread(() -> client.forward("POST", "/chat/sessions", "", new byte[0], inbound, 7));
		thread.start();
		// 等待第一个请求抵达 handler
		for (int i = 0; i < 100 && receivedXUserId.isEmpty(); i++) {
			Thread.sleep(10);
		}
		assertThrows(Errors.AgentBusy.class,
				() -> client.forward("POST", "/chat/sessions", "", new byte[0], inbound, 7));
		blockLatch.countDown();
		thread.join(5000);
		assertFalse(receivedXUserId.isEmpty());
		assertTrue(blockLatch.getCount() == 0);
	}

	private static final class FakeAgentTokenStore implements AgentTokenStore {

		@Override
		public void save(String token, java.time.Duration ttl) {
		}

		@Override
		public String get() {
			return "agent-token-123";
		}

	}

	private static final class FakeRandomHex implements RandomHex {

		@Override
		public String generate(int bytes) {
			return "ab".repeat(bytes);
		}

	}

	private static final class FakeLlmConfigRepository implements Repository {

		@Override
		public Stored find(long userId, String provider) {
			return new Stored(provider, "https://llm.example.com", "v1:enc", "gpt-4o", LocalDateTime.now());
		}

		@Override
		public List<Stored> findAll(long userId) {
			return List.of();
		}

		@Override
		public void upsert(long userId, String provider, String baseUrl, String apiKeyEnc, String model) {
		}

		@Override
		public void delete(long userId, String provider) {
		}

	}

	private static final class FakeSecret implements Secret {

		@Override
		public String encrypt(String plain) {
			return "v1:enc";
		}

		@Override
		public String decrypt(String cipher) {
			return "sk-real-key";
		}

	}

}