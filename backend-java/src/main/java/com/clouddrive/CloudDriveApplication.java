package com.clouddrive;

import com.clouddrive.config.DotEnvLoader;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * CloudDrive AI 后端（Java Spring Boot 版）。
 */
@SpringBootApplication
@EnableScheduling
public class CloudDriveApplication {

	public static void main(String[] args) {
		DotEnvLoader.load();
		new SpringApplicationBuilder(CloudDriveApplication.class).run(args);
	}

}
