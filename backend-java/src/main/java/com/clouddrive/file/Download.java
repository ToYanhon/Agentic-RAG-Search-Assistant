package com.clouddrive.file;

import java.io.InputStream;

/**
 * 下载结果（记录 + 内容流）。
 */
public record Download(Record record, InputStream body) {
}