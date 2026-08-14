package com.clouddrive.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.clouddrive.proxy.AgentClient;
import com.clouddrive.service.LLMConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * AgentController 代理头注入回归测试（B3）：
 * 恶意入站 X-User-Id/X-LLM-Key/X-Tavily-Key 不得透传到 agent，
 * agent 只应收到后端计算出的单一值。
 */
class AgentControllerTest {

    private static Enumeration<String> enumOf(String... values) {
        return Collections.enumeration(List.of(values));
    }

    @Test
    @SuppressWarnings("unchecked")
    void proxyStripsSensitiveInboundHeaders() throws Exception {
        AgentClient client = mock(AgentClient.class);
        when(client.tryAcquire()).thenReturn(true);
        when(client.baseUrl()).thenReturn("http://127.0.0.1:8000");
        HttpResponse<InputStream> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(resp.headers()).thenReturn(java.net.http.HttpHeaders.of(Map.of(), (n, v) -> true));
        when(client.send(any(HttpRequest.class))).thenReturn(resp);

        LLMConfigService llmCfg = mock(LLMConfigService.class);
        when(llmCfg.resolve(42L, "openai"))
                .thenReturn(new LLMConfigService.ResolveResult("https://llm.example/v1", "server-key", "model-x", true));
        when(llmCfg.resolve(42L, "tavily"))
                .thenReturn(new LLMConfigService.ResolveResult(null, null, null, false));

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getQueryString()).thenReturn(null);
        Map<String, List<String>> inbound = Map.of(
                "x-user-id", List.of("999"),
                "x-llm-key", List.of("attacker-key"),
                "x-llm-provider", List.of("openai"),
                "x-tavily-key", List.of("evil"),
                "accept", List.of("*/*"));
        when(request.getHeaderNames()).thenReturn(enumOf(inbound.keySet().toArray(String[]::new)));
        when(request.getHeaders(anyString())).thenAnswer(inv -> {
            String name = ((String) inv.getArgument(0)).toLowerCase();
            return enumOf(inbound.getOrDefault(name, List.of()).toArray(String[]::new));
        });
        when(request.getAttribute("user_id")).thenReturn(42L);
        when(request.getAttribute("request_id")).thenReturn("rid-1");
        when(request.getHeader("X-LLM-Provider")).thenReturn("openai");

        AgentController controller = new AgentController(client, llmCfg, new ObjectMapper());
        controller.listSessions(request, mock(HttpServletResponse.class));

        ArgumentCaptor<HttpRequest> cap = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(cap.capture());
        HttpRequest out = cap.getValue();

        assertThat(out.headers().allValues("X-User-Id")).containsExactly("42");
        assertThat(out.headers().allValues("X-LLM-Key")).containsExactly("server-key");
        assertThat(out.headers().allValues("X-LLM-Provider")).containsExactly("openai");
        assertThat(out.headers().allValues("X-Tavily-Key")).isEmpty();
        assertThat(out.headers().allValues("X-Agent-Token")).isEmpty();
    }

    @Test
    void hopByHopCoversSensitiveHeaders() throws Exception {
        java.lang.reflect.Field field = AgentController.class.getDeclaredField("HOP_BY_HOP");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> hop = (Set<String>) field.get(null);
        assertThat(hop).contains("x-user-id", "x-agent-token", "x-llm-provider",
                "x-llm-base-url", "x-llm-key", "x-llm-model", "x-tavily-key");
    }
}
