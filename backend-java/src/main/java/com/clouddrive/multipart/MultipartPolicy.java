package com.clouddrive.multipart;

import java.util.List;

/**
 * 分块上传纯领域规则，对应 Go multipart/policy.go。
 */
public final class MultipartPolicy {

	public static final long MIN_NON_FINAL_PART_SIZE = 5L * 1024 * 1024;

	private MultipartPolicy() {
	}

	public static int totalChunks(long size, long chunkSize) {
		if (size <= 0 || chunkSize <= 0) {
			throw new IllegalArgumentException("size and chunk size must be positive");
		}
		long total = (size - 1) / chunkSize + 1;
		if (total > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("too many parts");
		}
		return (int) total;
	}

	public static boolean validIndex(int index, int total) {
		return total > 0 && index >= 0 && index < total;
	}

	public static boolean contiguous(List<Integer> parts, int total) {
		if (parts.size() != total || total <= 0) {
			return false;
		}
		for (int i = 0; i < parts.size(); i++) {
			if (parts.get(i) != i) {
				return false;
			}
		}
		return true;
	}

	public static boolean validPartSize(int index, int total, long size) {
		return size > 0 && (index == total - 1 || size >= MIN_NON_FINAL_PART_SIZE);
	}

}