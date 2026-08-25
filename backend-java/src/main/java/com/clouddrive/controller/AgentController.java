package com.clouddrive.controller;

import com.clouddrive.agentproxy.AgentProxyClient;
import com.clouddrive.common.Envelope;
import com.clouddrive.common.ErrorCode;
import com.clouddrive.common.Errors;
import com.clouddrive.web.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpHeaders;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

/**
 * Agent 网关控制器，对应 Go httpapi/agent.go。白名单代理，保留上游状态/headers/body，SSE 流式透传。
 */
@RestController
public class AgentController {

	private static final int MAX_BODY = 32 << 20;

	private static final int BUFFER = 32 * 1024;

	private final AgentProxyClient proxy;

	private final ObjectMapper mapper;

	public AgentController(AgentProxyClient proxy, ObjectMapper mapper) {
		this.proxy = proxy;
		this.mapper = mapper;
	}

	@RequestMapping(value = "/api/v1/agent/chat/sessions", method = { RequestMethod.POST, RequestMethod.GET })
	public void sessions(HttpServletRequest request, HttpServletResponse response) throws IOException {
		proxy("/chat/sessions", request, response);
	}

	@RequestMapping(value = "/api/v1/agent/chat/sessions/{session_id}/messages",
			method = { RequestMethod.GET, RequestMethod.POST })
	public void sessionMessages(@PathVariable("session_id") String sessionId, HttpServletRequest request,
			HttpServletResponse response) throws IOException {
		proxy("/chat/sessions/" + sessionId + "/messages", request, response);
	}

	@PostMapping("/api/v1/agent/chat/sessions/{session_id}/messages/append")
	public void sessionMessagesAppend(@PathVariable("session_id") String sessionId, HttpServletRequest request,
			HttpServletResponse response) throws IOException {
		proxy("/chat/sessions/" + sessionId + "/messages/append", request, response);
	}

	@RequestMapping(value = "/api/v1/agent/chat/sessions/{session_id}",
			method = { RequestMethod.PUT, RequestMethod.DELETE })
	public void session(@PathVariable("session_id") String sessionId, HttpServletRequest request,
			HttpServletResponse response) throws IOException {
		proxy("/chat/sessions/" + sessionId, request, response);
	}

	@PostMapping("/api/v1/agent/summary/{file_id}")
	public void summary(@PathVariable("file_id") String fileId, HttpServletRequest request,
			HttpServletResponse response) throws IOException {
		proxy("/summary/" + fileId, request, response);
	}

	@PostMapping("/api/v1/agent/index/status")
	public void indexStatus(HttpServletRequest request, HttpServletResponse response) throws IOException {
		proxy("/index/status", request, response);
	}

	@RequestMapping(value = "/api/v1/agent/index/{file_id}", method = { RequestMethod.POST, RequestMethod.DELETE })
	public void index(@PathVariable("file_id") String fileId, HttpServletRequest request, HttpServletResponse response)
			throws IOException {
		proxy("/index/" + fileId, request, response);
	}

	@PostMapping("/api/v1/agent/index/folder/{folder_id}/status")
	public void folderIndexStatus(@PathVariable("folder_id") String folderId, HttpServletRequest request,
			HttpServletResponse response) throws IOException {
		proxy("/index/folder/" + folderId + "/status", request, response);
	}

	@RequestMapping(value = "/api/v1/agent/index/folder/{folder_id}",
			method = { RequestMethod.POST, RequestMethod.DELETE })
	public void folderIndex(@PathVariable("folder_id") String folderId, HttpServletRequest request,
			HttpServletResponse response) throws IOException {
		proxy("/index/folder/" + folderId, request, response);
	}

	@RequestMapping(value = "/api/v1/agent/memory", method = { RequestMethod.GET, RequestMethod.DELETE })
	public void memory(HttpServletRequest request, HttpServletResponse response) throws IOException {
		proxy("/memory", request, response);
	}

	private void proxy(String target, HttpServletRequest request, HttpServletResponse response) throws IOException {
		byte[] payload = readBounded(request.getInputStream());
		if (payload == null) {
			writeEnvelopeError(response, 400, ErrorCode.BAD_REQUEST, "invalid request body");
			return;
		}
		AgentProxyClient.ProxyResponse upstream;
		try {
			upstream = proxy.forward(request.getMethod(), target, request.getQueryString(), payload,
					headerSnapshot(request), UserContext.userId());
		}
		catch (Errors.AgentBusy e) {
			writeEnvelopeError(response, 503, ErrorCode.INTERNAL, "agent busy");
			return;
		}
		catch (RuntimeException e) {
			writeEnvelopeError(response, 502, ErrorCode.INTERNAL, "agent unavailable");
			return;
		}
		try (InputStream body = upstream.body()) {
			upstream.headers().map().forEach((name, values) -> {
				if (!AgentProxyClient.isBlocked(name) && !values.isEmpty()) {
					response.setHeader(name, values.get(0));
				}
			});
			response.setStatus(upstream.status());
			OutputStream out = response.getOutputStream();
			byte[] buffer = new byte[BUFFER];
			int read;
			while ((read = body.read(buffer)) != -1) {
				if (read > 0) {
					out.write(buffer, 0, read);
					out.flush();
				}
			}
		}
	}

	private static HttpHeaders headerSnapshot(HttpServletRequest request) {
		Map<String, List<String>> map = new java.util.LinkedHashMap<>();
		Enumeration<String> names = request.getHeaderNames();
		while (names != null && names.hasMoreElements()) {
			String name = names.nextElement();
			Enumeration<String> values = request.getHeaders(name);
			map.put(name, Collections.list(values));
		}
		return HttpHeaders.of(map, (name, value) -> true);
	}

	private static byte[] readBounded(InputStream in) throws IOException {
		byte[] buffer = new byte[MAX_BODY + 1];
		int total = 0;
		int read;
		while (total < buffer.length && (read = in.read(buffer, total, buffer.length - total)) != -1) {
			total += read;
		}
		if (total > MAX_BODY) {
			return null;
		}
		byte[] result = new byte[total];
		System.arraycopy(buffer, 0, result, 0, total);
		return result;
	}

	private void writeEnvelopeError(HttpServletResponse response, int status, int code, String message)
			throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getWriter().write(mapper.writeValueAsString(Envelope.error(status, code, message)));
	}

}