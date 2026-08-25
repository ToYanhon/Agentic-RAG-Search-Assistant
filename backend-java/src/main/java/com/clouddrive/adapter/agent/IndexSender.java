package com.clouddrive.adapter.agent;

import com.clouddrive.auth.AgentTokenStore;
import com.clouddrive.indexnotify.Sender;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Agent 索引通知 HTTP 出站适配器，对应 Go adapter/agent.IndexSender。 reindex -> POST
 * /index/{id}；unindex -> DELETE /index/{id}；HTTP/1.1，10s 超时。
 */
@Component
public class IndexSender implements Sender {

	private final String baseUrl;

	private final AgentTokenStore tokenStore;

	private final HttpClient client;

	public IndexSender(com.clouddrive.config.AppProperties properties, AgentTokenStore tokenStore) {
		String raw = properties.getAgentBaseUrl();
		this.baseUrl = raw == null ? "" : raw.replaceAll("/+$", "");
		this.tokenStore = tokenStore;
		this.client = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(10))
			.build();
	}

	@Override
	public boolean send(String kind, long fileId, long ownerId) {
		if (baseUrl.isEmpty()) {
			return true;
		}
		String token;
		try {
			token = tokenStore.get();
		}
		catch (RuntimeException e) {
			return false;
		}
		String method = "unindex".equals(kind) ? "DELETE" : "POST";
		HttpRequest.Builder builder = HttpRequest.newBuilder()
			.uri(URI.create(baseUrl + "/index/" + fileId))
			.header("X-User-Id", Long.toString(ownerId))
			.timeout(Duration.ofSeconds(10));
		if (token != null && !token.isEmpty()) {
			builder.header("X-Agent-Token", token);
		}
		HttpRequest request = switch (method) {
			case "DELETE" -> builder.DELETE().build();
			default -> builder.POST(HttpRequest.BodyPublishers.noBody()).build();
		};
		try {
			HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
			return response.statusCode() >= 200 && response.statusCode() < 300;
		}
		catch (IOException | InterruptedException e) {
			return false;
		}
	}

}