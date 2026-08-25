package com.clouddrive.web;

import com.clouddrive.auth.AgentTokenManager;
import com.clouddrive.auth.AuthService;
import com.clouddrive.auth.Claims;
import com.clouddrive.common.Errors;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthFilterTest {

	private AuthService service;

	private AgentTokenManager agents;

	private AuthFilter filter;

	@BeforeEach
	void setUp() {
		service = mock(AuthService.class);
		agents = mock(AgentTokenManager.class);
		filter = new AuthFilter(service, agents, new ObjectMapper());
	}

	private MockHttpServletResponse run(MockHttpServletRequest request) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		filter.doFilter(request, response, chain);
		return response;
	}

	@Test
	void missingTokenRejected() throws Exception {
		MockHttpServletResponse response = run(new MockHttpServletRequest("GET", "/api/v1/files"));
		assertEquals(401, response.getStatus());
		assertTrue(response.getContentAsString().contains("missing or malformed token"));
	}

	@Test
	void invalidAgentTokenRejected() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
		request.addHeader("X-Agent-Token", "bad");
		request.setParameter("user_id", "5");
		when(agents.validate("bad")).thenReturn(false);
		MockHttpServletResponse response = run(request);
		assertEquals(401, response.getStatus());
		assertTrue(response.getContentAsString().contains("invalid agent token"));
	}

	@Test
	void agentTokenMissingUserIdRejected() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
		request.addHeader("X-Agent-Token", "good");
		when(agents.validate("good")).thenReturn(true);
		MockHttpServletResponse response = run(request);
		assertEquals(401, response.getStatus());
		assertTrue(response.getContentAsString().contains("missing user_id for internal call"));
	}

	@Test
	void agentTokenSetsAgentCaller() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
		request.addHeader("X-Agent-Token", "good");
		request.setParameter("user_id", "5");
		when(agents.validate("good")).thenReturn(true);
		MockHttpServletResponse response = new MockHttpServletResponse();
		long[] captured = new long[1];
		Caller[] capturedCaller = new Caller[1];
		MockFilterChain chain = new MockFilterChain() {
			@Override
			public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
				captured[0] = UserContext.userId();
				capturedCaller[0] = UserContext.caller();
			}
		};
		filter.doFilter(request, response, chain);
		assertEquals(5L, captured[0]);
		assertEquals(Caller.AGENT, capturedCaller[0]);
	}

	@Test
	void bearerValidSetsUserCaller() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
		request.addHeader("Authorization", "Bearer jwt-1");
		when(service.authenticate("jwt-1")).thenReturn(new Claims(9, "alice", "jti", Instant.now().plusSeconds(3600)));
		MockHttpServletResponse response = new MockHttpServletResponse();
		long[] captured = new long[1];
		Caller[] capturedCaller = new Caller[1];
		MockFilterChain chain = new MockFilterChain() {
			@Override
			public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
				captured[0] = UserContext.userId();
				capturedCaller[0] = UserContext.caller();
			}
		};
		filter.doFilter(request, response, chain);
		assertEquals(9L, captured[0]);
		assertEquals(Caller.USER, capturedCaller[0]);
	}

	@Test
	void bearerInvalidReturnsExpired() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
		request.addHeader("Authorization", "Bearer bad");
		when(service.authenticate("bad")).thenThrow(new Errors.TokenInvalid("invalid or expired token"));
		MockHttpServletResponse response = run(request);
		assertEquals(401, response.getStatus());
		assertTrue(response.getContentAsString().contains("invalid or expired token"));
	}

	@Test
	void queryTokenFallbackOnlyForDownloadSuffix() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files/1/download");
		request.setParameter("token", "jwt-query");
		when(service.authenticate("jwt-query")).thenReturn(new Claims(3, "u", "jti", Instant.now().plusSeconds(3600)));
		run(request);
		verify(service).authenticate("jwt-query");
	}

	@Test
	void queryTokenRejectedForNonDownloadRoute() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
		request.setParameter("token", "jwt-query");
		MockHttpServletResponse response = run(request);
		assertEquals(401, response.getStatus());
	}

	@Test
	void publicRoutesSkipAuth() throws Exception {
		MockHttpServletResponse response = run(new MockHttpServletRequest("POST", "/api/v1/auth/register"));
		assertEquals(200, response.getStatus());
	}

}