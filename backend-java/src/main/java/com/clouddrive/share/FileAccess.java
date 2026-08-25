package com.clouddrive.share;

import com.clouddrive.file.Record;

import java.io.InputStream;

/**
 * 分享文件访问端口，对应 Go share.Files / share.Objects。
 */
public interface FileAccess {

	Record find(long fileId);

	InputStream get(String objectKey);

	InputStream getRange(String objectKey, long offset, long length);

}