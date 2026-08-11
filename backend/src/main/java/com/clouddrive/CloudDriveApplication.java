package com.clouddrive;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * CloudDrive AI 后端入口（Java/Spring Boot 迁移版）。
 * 契约对齐 Go backend：{@code {code,message,data}} 信封、错误码、双认证等。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class CloudDriveApplication {

    public static void main(String[] args) {
        SpringApplication.run(CloudDriveApplication.class, args);
    }
}
