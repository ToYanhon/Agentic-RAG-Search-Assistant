package com.clouddrive.security;

import java.io.IOException;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.clouddrive.common.ErrorCode;
import com.clouddrive.common.Resp;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 双认证过滤器，对齐 Go middleware.Auth：
 * 1. X-Agent-Token（agent→后端内部调用，user_id 取 query 或 X-User-Id 头）
 * 2. Bearer JWT（user_id + jti 黑名单校验）
 * 仅拦截 /api/v1/**；公开端点（login/register/logout）与 /s/** 分享路由放行。
 */
@Component
public class AuthFilter extends OncePerRequestFilter {

    private final JwtService jwt;
    private final TokenBlacklist blacklist;
    private final AgentTokenManager agentToken;
    private final ObjectMapper objectMapper;

    public AuthFilter(JwtService jwt, TokenBlacklist blacklist,
                      AgentTokenManager agentToken, ObjectMapper objectMapper) {
        this.jwt = jwt;
        this.blacklist = blacklist;
        this.agentToken = agentToken;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (isPublicEndpoint(request)) {
            chain.doFilter(request, response);
            return;
        }

        String agentToken = request.getHeader("X-Agent-Token");
        if (agentToken != null && !agentToken.isEmpty()) {
            if (this.agentToken.validate(agentToken)) {
                String uid = request.getParameter("user_id");
                if (uid == null || uid.isEmpty()) {
                    uid = request.getHeader("X-User-Id");
                }
                long id;
                try {
                    id = Long.parseLong(uid);
                } catch (Exception e) {
                    id = 0;
                }
                if (id <= 0) {
                    writeError(response, ErrorCode.UNAUTHORIZED, "missing user_id for internal call");
                    return;
                }
                request.setAttribute("user_id", id);
                request.setAttribute("caller", "agent");
                chain.doFilter(request, response);
                return;
            }
            writeError(response, ErrorCode.UNAUTHORIZED, "invalid agent token");
            return;
        }

        String auth = request.getHeader("Authorization");
        if (auth == null || auth.length() < 8 || !auth.startsWith("Bearer ")) {
            writeError(response, ErrorCode.UNAUTHORIZED, "missing or malformed token");
            return;
        }
        JwtService.TokenData td;
        try {
            td = jwt.parse(auth.substring(7));
        } catch (JwtException | IllegalArgumentException e) {
            writeError(response, ErrorCode.TOKEN_EXPIRED, "invalid or expired token");
            return;
        }
        if (blacklist.isBlacklisted(td.jti())) {
            writeError(response, ErrorCode.TOKEN_EXPIRED, "token has been revoked");
            return;
        }
        request.setAttribute("user_id", td.userId());
        request.setAttribute("username", td.username());
        request.setAttribute("caller", "user");
        chain.doFilter(request, response);
    }

    private boolean isPublicEndpoint(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if (!path.startsWith("/api/v1/auth/")) {
            return false;
        }
        return "POST".equals(method)
                && (path.endsWith("/login") || path.endsWith("/register") || path.endsWith("/logout"));
    }

    private void writeError(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setStatus(code.getHttpStatus());
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getOutputStream(), Resp.error(code.getCode(), message));
    }
}
