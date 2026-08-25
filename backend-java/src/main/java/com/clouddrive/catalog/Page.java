package com.clouddrive.catalog;

import java.util.List;

/**
 * 分页文件列表，对应 Go catalog.Page。
 */
public record Page(List<File> files, long total, int page) {
}