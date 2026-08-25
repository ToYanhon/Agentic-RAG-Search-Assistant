package com.clouddrive.multipart;

import java.time.Duration;
import java.util.List;

/**
 * multipart 元数据端口，对应 Go multipart.Metadata（Redis Hash 存储）。
 */
public interface Metadata {

	Meta get(String uploadId);

	void save(String uploadId, Meta meta, Duration ttl);

	void savePart(String uploadId, int index, String etag, Duration ttl);

	List<Integer> receivedParts(String uploadId);

	String partEtag(String uploadId, int index);

	void delete(String uploadId);

	boolean exists(String uploadId);

}