package com.clouddrive.adapter;

import com.clouddrive.file.ObjectStore;
import com.clouddrive.file.Repository;
import com.clouddrive.share.FileAccess;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * 分享文件访问适配器：文件记录查 MySQL，内容流走 MinIO。
 */
@Component
public class ShareFileAccess implements FileAccess {

	private final Repository files;

	private final ObjectStore objects;

	public ShareFileAccess(Repository files, ObjectStore objects) {
		this.files = files;
		this.objects = objects;
	}

	@Override
	public com.clouddrive.file.Record find(long fileId) {
		return files.find(fileId);
	}

	@Override
	public InputStream get(String objectKey) {
		return objects.get(objectKey);
	}

	@Override
	public InputStream getRange(String objectKey, long offset, long length) {
		return objects.getRange(objectKey, offset, length);
	}

}