package com.clouddrive.adapter.storage;

import com.clouddrive.config.AppProperties;
import com.clouddrive.file.ObjectStore;
import com.clouddrive.multipart.IncompleteUpload;
import com.clouddrive.multipart.Part;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;
import software.amazon.awssdk.services.s3.model.MultipartUpload;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.paginators.ListMultipartUploadsIterable;

import jakarta.annotation.PostConstruct;

import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * MinIO（S3 兼容）对象存储适配器，对应 Go adapter/storage.MinIO。 使用 AWS S3 SDK v2 path-style 访问
 * MinIO；multipart 使用 S3 multipart API。
 */
@org.springframework.stereotype.Component
public class MinioObjectStore implements ObjectStore, com.clouddrive.multipart.ObjectStore {

	private final S3Client client;

	private final String bucket;

	public MinioObjectStore(AppProperties properties) {
		this.client = S3Client.builder()
			.endpointOverride(URI.create(normalize(properties.getMinioEndpoint())))
			.region(Region.US_EAST_1)
			.forcePathStyle(true)
			.credentialsProvider(StaticCredentialsProvider
				.create(AwsBasicCredentials.create(properties.getMinioAccessKey(), properties.getMinioSecretKey())))
			.httpClientBuilder(UrlConnectionHttpClient.builder())
			.build();
		this.bucket = properties.getMinioBucket();
	}

	@PostConstruct
	public void init() {
		ensureBucket();
	}

	public void ensureBucket() {
		try {
			client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
		}
		catch (S3Exception e) {
			client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
		}
	}

	@Override
	public void put(String key, String contentType, InputStream body, long size) {
		client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
				RequestBody.fromInputStream(body, size));
	}

	@Override
	public InputStream get(String key) {
		return client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
	}

	@Override
	public InputStream getRange(String key, long offset, long length) {
		String range = "bytes=" + offset + "-" + (offset + length - 1);
		return client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).range(range).build());
	}

	@Override
	public void delete(String key) {
		client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
	}

	@Override
	public void copy(String sourceKey, String destinationKey) {
		client.copyObject(CopyObjectRequest.builder()
			.sourceBucket(bucket)
			.sourceKey(sourceKey)
			.destinationBucket(bucket)
			.destinationKey(destinationKey)
			.build());
	}

	@Override
	public String createMultipart(String key, String contentType) {
		CreateMultipartUploadResponse response = client.createMultipartUpload(
				CreateMultipartUploadRequest.builder().bucket(bucket).key(key).contentType(contentType).build());
		return response.uploadId();
	}

	@Override
	public String uploadPart(String key, String uploadId, int partNumber, InputStream body, long size) {
		return client
			.uploadPart(UploadPartRequest.builder()
				.bucket(bucket)
				.key(key)
				.uploadId(uploadId)
				.partNumber(partNumber)
				.contentLength(size)
				.build(), RequestBody.fromInputStream(body, size))
			.eTag();
	}

	@Override
	public void completeMultipart(String key, String uploadId, List<Part> parts) {
		List<CompletedPart> completed = new ArrayList<>(parts.size());
		for (Part part : parts) {
			completed.add(CompletedPart.builder().partNumber(part.number()).eTag(part.etag()).build());
		}
		client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
			.bucket(bucket)
			.key(key)
			.uploadId(uploadId)
			.multipartUpload(CompletedMultipartUpload.builder().parts(completed).build())
			.build());
	}

	@Override
	public void abortMultipart(String key, String uploadId) {
		client.abortMultipartUpload(
				AbortMultipartUploadRequest.builder().bucket(bucket).key(key).uploadId(uploadId).build());
	}

	@Override
	public long headSize(String key) {
		return client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()).contentLength();
	}

	@Override
	public List<IncompleteUpload> incompleteUploads() {
		List<IncompleteUpload> uploads = new ArrayList<>();
		ListMultipartUploadsIterable pages = client.listMultipartUploadsPaginator(
				ListMultipartUploadsRequest.builder().bucket(bucket).prefix("users/").build());
		for (software.amazon.awssdk.services.s3.model.ListMultipartUploadsResponse page : pages) {
			for (MultipartUpload upload : page.uploads()) {
				uploads.add(new IncompleteUpload(upload.key(), upload.uploadId()));
			}
		}
		return uploads;
	}

	private static String normalize(String endpoint) {
		String value = endpoint;
		if (value != null && !value.startsWith("http://") && !value.startsWith("https://")) {
			value = "http://" + value;
		}
		return value;
	}

}