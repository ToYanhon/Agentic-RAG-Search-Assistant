package com.clouddrive.file;

import java.io.InputStream;

/**
 * 对象存储端口，对应 Go file.ObjectStore。
 */
public interface ObjectStore {

	void put(String key, String contentType, InputStream body, long size);

	InputStream get(String key);

	void delete(String key);

	void copy(String sourceKey, String destinationKey);

	InputStream getRange(String key, long offset, long length);

}