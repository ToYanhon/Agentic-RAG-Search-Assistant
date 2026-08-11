package com.clouddrive.config;

import java.net.URI;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/**
 * S3 客户端装配（面向 MinIO 端点，S3 兼容）。
 * 用 AWS SDK v2 以获得原生 S3 multipart；region 任意（MinIO 忽略）。
 */
@Configuration
public class MinioConfig {

    @Bean
    public S3Client s3Client(AppProperties props) {
        AppProperties.Minio m = props.getMinio();
        String scheme = m.isUseSsl() ? "https://" : "http://";
        return S3Client.builder()
                .endpointOverride(URI.create(scheme + m.getEndpoint()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(m.getAccessKey(), m.getSecretKey())))
                // MinIO 用 IP/端口端点，须 path-style 寻址（虚拟主机风格不适用）
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }
}
