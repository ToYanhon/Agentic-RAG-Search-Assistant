package com.clouddrive.web;

import com.clouddrive.auth.AgentTokenManager;
import com.clouddrive.auth.AuthService;
import com.clouddrive.auth.Claims;
import com.clouddrive.common.Envelope;
import com.clouddrive.common.ErrorCode;
import com.clouddrive.common.Errors;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 认证中间件，对应 Go httpapi.protected()。 优先级：X-Agent-Token（agent 身份）> Bearer JWT（user 身份，仅
 * /download 后缀允许 ?token= 回退）。
 */
@Component
public class AuthFilter extends OncePerRequestFilter {

	private final AuthService service;

	private final AgentTokenManager agents;

	private final ObjectMapper mapper;

	public AuthFilter(AuthService service, AgentTokenManager agents, ObjectMapper mapper) {
		this.service = service;
		this.agents = agents;
		this.mapper = mapper;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI();
		if ("/health".equals(path) || path.startsWith("/s/")) {
			return true;
		}
		if (!path.startsWith("/api/v1")) {
			return true;
		}
		return path.equals("/api/v1/auth/register") || path.equals("/api/v1/auth/login")
				|| path.equals("/api/v1/auth/logout");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		UserContext.clear();
		try {
			String agentToken = request.getHeader("X-Agent-Token");
			if (agentToken != null && !agentToken.isEmpty()) {
				String rawUserId = first(request.getParameter("user_id"), request.getHeader("X-User-Id"));
				if (!agents.validate(agentToken)) {
					writeError(response, 401, ErrorCode.UNAUTHORIZED, "invalid agent token");
					return;
				}
				long userId;
				try {
					userId = Long.parseLong(rawUserId);
				}
				catch (NumberFormatException e) {
					userId = 0;
				}
				if (userId <= 0) {
					writeError(response, 401, ErrorCode.UNAUTHORIZED, "missing user_id for internal call");
					return;
				}
				UserContext.set(userId, Caller.AGENT);
				chain.doFilter(request, response);
				return;
			}

			String raw = bearer(request);
			if (raw.isEmpty() && request.getRequestURI().endsWith("/download")) {
				raw = request.getParameter("token");
			}
			if (raw.isEmpty()) {
				writeError(response, 401, ErrorCode.UNAUTHORIZED, "missing or malformed token");
				return;
			}
			Claims claims;
			try {
				claims = service.authenticate(raw);
			}
			catch (Errors.TokenInvalid e) {
				writeError(response, 401, ErrorCode.TOKEN_EXPIRED, "invalid or expired token");
				return;
			}
			UserContext.set(claims.userId(), Caller.USER);
			chain.doFilter(request, response);
		}
		finally {
			UserContext.clear();
		}
	}

	private void writeError(HttpServletResponse response, int status, int code, String message) throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		mapper.writeValue(response.getWriter(), Envelope.error(status, code, message));
	}

	private static String bearer(HttpServletRequest request) {
		String value = request.getHeader("Authorization");
		if (value == null || !value.startsWith("Bearer ")) {
			return "";
		}
		return value.substring("Bearer ".length());
	}

	private static String first(String... values) {
		for (String value : values) {
			if (value != null && !value.isEmpty()) {
				return value;
			}
		}
		return "";
	}

}