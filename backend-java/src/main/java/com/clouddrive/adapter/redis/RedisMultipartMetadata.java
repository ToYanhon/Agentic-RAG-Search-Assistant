package com.clouddrive.adapter.redis;

import com.clouddrive.common.Errors;
import com.clouddrive.multipart.Metadata;
import com.clouddrive.multipart.Meta;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * multipart 元数据 Redis 实现，对应 Go adapter/redis.MultipartMetadata。 key:
 * multipart:{id}（Hash）与 multipart:{id}:parts（Hash，0 基索引 -> ETag），24h TTL。
 */
@Component
public class RedisMultipartMetadata implements Metadata {

	private final StringRedisTemplate redis;

	public RedisMultipartMetadata(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public Meta get(String uploadId) {
		Map<Object, Object> values = redis.opsForHash().entries(key(uploadId));
		if (values.isEmpty()) {
			throw new Errors.UploadNotFound("upload not found");
		}
		long owner = parseLong(str(values.get("owner_id")));
		long size = parseLong(str(values.get("size")));
		long chunk = parseLong(str(values.get("chunk_size")));
		int total = (int) parseLong(str(values.get("total_chunks")));
		Long folder = null;
		long folderValue = parseLong(str(values.get("folder_id")));
		if (folderValue > 0) {
			folder = folderValue;
		}
		return new Meta(owner, str(values.get("name")), size, str(values.get("mime_type")), folder,
				str(values.get("md5")), chunk, total, str(values.get("object_key")), str(values.get("upload_id")), 0);
	}

	@Override
	public void save(String uploadId, Meta meta, Duration ttl) {
		String folder = "0";
		if (meta.folderId() != null) {
			folder = Long.toString(meta.folderId());
		}
		Map<String, String> values = new HashMap<>();
		values.put("owner_id", Long.toString(meta.ownerId()));
		values.put("name", meta.name());
		values.put("size", Long.toString(meta.size()));
		values.put("mime_type", meta.mimeType());
		values.put("folder_id", folder);
		values.put("md5", meta.md5());
		values.put("chunk_size", Long.toString(meta.chunkSize()));
		values.put("total_chunks", Integer.toString(meta.totalChunks()));
		values.put("object_key", meta.objectKey());
		values.put("upload_id", meta.uploadId());
		redis.executePipelined((RedisCallback<Object>) connection -> {
			StringRedisConnection conn = (StringRedisConnection) connection;
			conn.hMSet(key(uploadId), values);
			conn.expire(key(uploadId), ttl.getSeconds());
			return null;
		});
	}

	@Override
	public void savePart(String uploadId, int index, String etag, Duration ttl) {
		redis.executePipelined((RedisCallback<Object>) connection -> {
			StringRedisConnection conn = (StringRedisConnection) connection;
			conn.hSet(partsKey(uploadId), Integer.toString(index), etag);
			conn.expire(partsKey(uploadId), ttl.getSeconds());
			conn.expire(key(uploadId), ttl.getSeconds());
			return null;
		});
	}

	@Override
	public List<Integer> receivedParts(String uploadId) {
		List<Object> keys = new ArrayList<>(redis.opsForHash().keys(partsKey(uploadId)));
		List<Integer> parts = new ArrayList<>();
		for (Object key : keys) {
			try {
				parts.add(Integer.parseInt((String) key));
			}
			catch (NumberFormatException ignored) {
				// 忽略非法索引
			}
		}
		Collections.sort(parts);
		return parts;
	}

	@Override
	public String partEtag(String uploadId, int index) {
		return (String) redis.opsForHash().get(partsKey(uploadId), Integer.toString(index));
	}

	@Override
	public void delete(String uploadId) {
		redis.delete(List.of(key(uploadId), partsKey(uploadId)));
	}

	@Override
	public boolean exists(String uploadId) {
		return Boolean.TRUE.equals(redis.hasKey(key(uploadId)));
	}

	private static String key(String uploadId) {
		return "multipart:" + uploadId;
	}

	private static String partsKey(String uploadId) {
		return "multipart:" + uploadId + ":parts";
	}

	private static long parseLong(String value) {
		if (value == null || value.isEmpty()) {
			return 0;
		}
		try {
			return Long.parseLong(value);
		}
		catch (NumberFormatException e) {
			return 0;
		}
	}

	private static String str(Object value) {
		return value == null ? "" : value.toString();
	}

}