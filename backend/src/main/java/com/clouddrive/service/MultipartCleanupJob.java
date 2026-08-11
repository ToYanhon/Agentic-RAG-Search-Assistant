package com.clouddrive.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.clouddrive.config.AppProperties;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsResponse;
import software.amazon.awssdk.services.s3.model.MultipartUpload;

/**
 * 分块上传清理器（对齐 Go MultipartService.CleanupExpired，30 分钟一轮）：
 * 列出 MinIO 未完成 multipart，跳过非 users/ 前缀；Redis 键已过期（不存在）则 abort。
 */
@Component
public class MultipartCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(MultipartCleanupJob.class);

    private final S3Client s3;
    private final StringRedisTemplate redis;
    private final String bucket;

    public MultipartCleanupJob(S3Client s3, StringRedisTemplate redis, AppProperties props) {
        this.s3 = s3;
        this.redis = redis;
        this.bucket = props.getMinio().getBucket();
    }

    @Scheduled(fixedDelay = 30 * 60 * 1000, initialDelay = 30 * 60 * 1000)
    public void cleanup() {
        try {
            ListMultipartUploadsResponse res = s3.listMultipartUploads(
                    ListMultipartUploadsRequest.builder().bucket(bucket).build());
            int aborted = 0;
            for (MultipartUpload u : res.uploads()) {
                if (!u.key().startsWith("users/")) {
                    continue;
                }
                Boolean alive = redis.hasKey("multipart:" + u.uploadId());
                if (Boolean.TRUE.equals(alive)) {
                    continue;
                }
                s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                        .bucket(bucket).key(u.key()).uploadId(u.uploadId()).build());
                aborted++;
            }
            if (aborted > 0) {
                log.info("multipart cleanup aborted {} expired uploads", aborted);
            }
        } catch (Exception e) {
            log.warn("multipart cleanup failed", e);
        }
    }
}
