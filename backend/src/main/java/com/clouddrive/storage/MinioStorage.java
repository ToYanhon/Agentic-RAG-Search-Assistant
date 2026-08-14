package com.clouddrive.storage;

import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.clouddrive.config.AppProperties;

import jakarta.annotation.PostConstruct;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

/**
 * 对象存储封装（对齐 Go pkg/storage），基于 AWS SDK v2 S3Client（面向 MinIO）。
 * 原生 S3 multipart（Create/UploadPart/Complete/Abort）。
 */
@Component
public class MinioStorage {

    private static final Logger log = LoggerFactory.getLogger(MinioStorage.class);

    /** S3 DeleteObjects 单请求上限（AWS 硬限制 1000）。 */
    private static final int DELETE_BATCH = 1000;

    private final S3Client client;
    private final String bucket;

    public MinioStorage(S3Client client, AppProperties props) {
        this.client = client;
        this.bucket = props.getMinio().getBucket();
    }

    @PostConstruct
    void ensureBucket() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            try {
                client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                log.info("bucket created: {}", bucket);
            } catch (BucketAlreadyOwnedByYouException ignored) {
            } catch (S3Exception ex) {
                log.warn("bucket create failed (may already exist): {}", ex.getMessage());
            }
        }
    }

    public void upload(String objectKey, InputStream in, long size, String contentType) {
        PutObjectRequest req = PutObjectRequest.builder()
                .bucket(bucket).key(objectKey)
                .contentType(contentType == null ? "application/octet-stream" : contentType)
                .build();
        client.putObject(req, RequestBody.fromInputStream(in, size));
    }

    public ResponseInputStream<?> download(String objectKey) {
        return client.getObject(GetObjectRequest.builder().bucket(bucket).key(objectKey).build());
    }

    /** 实测对象字节数（服务端权威值，用于分块上传 complete 前校验实际分块总和）。 */
    public long headObjectSize(String objectKey) {
        return client.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build())
                .contentLength();
    }

    public void delete(String objectKey) {
        client.deleteObject(builder -> builder.bucket(bucket).key(objectKey).build());
    }

    public void deleteObjects(List<String> objectKeys) {
        if (objectKeys.isEmpty()) {
            return;
        }
        // 单请求上限 1000：按 1000 分页循环；响应 errors 仅告警，不阻断记录删除（D6）
        for (int i = 0; i < objectKeys.size(); i += DELETE_BATCH) {
            List<String> batch = objectKeys.subList(i, Math.min(i + DELETE_BATCH, objectKeys.size()));
            List<ObjectIdentifier> ids = batch.stream()
                    .map(k -> ObjectIdentifier.builder().key(k).build())
                    .toList();
            DeleteObjectsResponse resp = client.deleteObjects(DeleteObjectsRequest.builder()
                    .bucket(bucket)
                    .delete(Delete.builder().objects(ids).build())
                    .build());
            if (resp.hasErrors()) {
                String failed = resp.errors().stream()
                        .map(e -> e.key() + "(" + e.code() + ")")
                        .collect(Collectors.joining(", "));
                log.warn("minio deleteObjects partial failure: {}", failed);
            }
        }
    }

    /** 服务端拷贝到新 key（秒传省带宽，每条记录独立对象，删除互不影响）。 */
    public void copyObject(String srcKey, String dstKey) {
        client.copyObject(CopyObjectRequest.builder()
                .sourceBucket(bucket).sourceKey(srcKey)
                .destinationBucket(bucket).destinationKey(dstKey)
                .build());
    }

    public String createMultipartUpload(String objectKey, String contentType) {
        return client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket).key(objectKey)
                .contentType(contentType == null ? "application/octet-stream" : contentType)
                .build()).uploadId();
    }

    /** 上传单个分块；partNumber 从 1 开始。返回 etag。 */
    public String uploadPart(String objectKey, String uploadId, int partNumber, InputStream in, long size) {
        return client.uploadPart(UploadPartRequest.builder()
                        .bucket(bucket).key(objectKey).uploadId(uploadId).partNumber(partNumber)
                        .build(),
                RequestBody.fromInputStream(in, size))
                .eTag();
    }

    /** 合并所有分块完成上传。parts 为 [(partNumber, etag)]，partNumber 从 1 起。 */
    public void completeMultipartUpload(String objectKey, String uploadId, List<CompletedPart> parts) {
        client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                .bucket(bucket).key(objectKey).uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build())
                .build());
    }

    public void abortMultipartUpload(String objectKey, String uploadId) {
        client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                .bucket(bucket).key(objectKey).uploadId(uploadId)
                .build());
    }
}
