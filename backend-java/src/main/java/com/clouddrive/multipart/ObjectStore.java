package com.clouddrive.multipart;

import java.io.InputStream;
import java.util.List;

/**
 * multipart 对象存储端口，对应 Go multipart.ObjectStore（MinIO S3 multipart API）。
 */
public interface ObjectStore {

	String createMultipart(String key, String contentType);

	String uploadPart(String key, String uploadId, int partNumber, InputStream body, long size);

	void completeMultipart(String key, String uploadId, List<Part> parts);

	void abortMultipart(String key, String uploadId);

	long headSize(String key);

	void delete(String key);

	List<IncompleteUpload> incompleteUploads();

}