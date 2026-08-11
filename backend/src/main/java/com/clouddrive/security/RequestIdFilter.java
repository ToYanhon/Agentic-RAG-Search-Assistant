package com.clouddrive.security;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Request-ID（对齐 Go middleware.RequestID）：复用入站 X-Request-Id（≤64），
 * 否则随机 16 字节 hex；写响应头并挂到 request 属性，供 agent 代理透传做链路追踪。
 */
@Component
public class RequestIdFilter extends OncePerRequestFilter {

    private static final int MAX_LEN = 64;
    private final SecureRandom random = new SecureRandom();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String rid = request.getHeader("X-Request-Id");
        if (rid == null || rid.isEmpty() || rid.length() > MAX_LEN) {
            byte[] bytes = new byte[16];
            random.nextBytes(bytes);
            rid = HexFormat.of().formatHex(bytes);
        }
        response.setHeader("X-Request-Id", rid);
        request.setAttribute("request_id", rid);
        chain.doFilter(request, response);
    }
}
