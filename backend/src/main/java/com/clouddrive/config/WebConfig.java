package com.clouddrive.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS 配置，对齐 Go middleware.CORS：允许任意来源、常用方法，
 * 允许 X-Tavily-Key 请求头（前端经后端透传联网 key 的场景），
 * 暴露 Content-Disposition（下载文件名）。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Content-Length", "Content-Disposition")
                .maxAge(3600);
    }
}
