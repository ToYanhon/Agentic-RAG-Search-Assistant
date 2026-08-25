package com.clouddrive.web;

import com.clouddrive.common.TimeUtil;
import com.clouddrive.file.Record;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 响应工具，对应 Go httpapi 的 fileResponse / pagination。
 */
public final class Responses {

	private Responses() {
	}

	public static Map<String, Object> fileResponse(Record record) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("id", record.id());
		data.put("name", record.name());
		data.put("size", record.size());
		data.put("mime_type", record.mimeType());
		data.put("md5", record.md5());
		data.put("folder_id", record.folderId());
		data.put("created_at", TimeUtil.format(record.createdAt()));
		return data;
	}

	public static int[] pagination(String pageRaw, String pageSizeRaw) {
		int page = parseInt(pageRaw);
		int pageSize = parseInt(pageSizeRaw);
		if (page < 1) {
			page = 1;
		}
		if (pageSize < 1 || pageSize > 100) {
			pageSize = 20;
		}
		return new int[] { page, pageSize };
	}

	private static int parseInt(String value) {
		if (value == null || value.isEmpty()) {
			return 0;
		}
		try {
			return Integer.parseInt(value);
		}
		catch (NumberFormatException e) {
			return 0;
		}
	}

}