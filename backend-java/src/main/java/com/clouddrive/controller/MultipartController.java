package com.clouddrive.controller;

import com.clouddrive.common.ApiException;
import com.clouddrive.common.Envelope;
import com.clouddrive.common.ErrorCode;
import com.clouddrive.file.Record;
import com.clouddrive.multipart.Meta;
import com.clouddrive.multipart.MultipartService;
import com.clouddrive.web.Responses;
import com.clouddrive.web.UserContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 分块上传控制器，对应 Go httpapi/multipart.go。
 */
@RestController
public class MultipartController {

	private final MultipartService service;

	private final long chunkMaxBytes;

	private final long fileMaxBytes;

	public MultipartController(MultipartService service, com.clouddrive.config.AppProperties properties) {
		this.service = service;
		this.chunkMaxBytes = properties.getUploadChunkMaxBytes();
		this.fileMaxBytes = properties.getUploadFileMaxBytes();
	}

	public record MultipartInitRequest(String name, Long size, String mime_type, Long folder_id, String md5,
			Long chunk_size) {
	}

	@PostMapping("/api/v1/files/multipart/init")
	public ResponseEntity<Envelope<Map<String, Object>>> init(@RequestBody MultipartInitRequest request) {
		if (request.name() == null || request.name().isEmpty()) {
			throw ApiException.badRequest("name required");
		}
		long size = request.size() == null ? 0 : request.size();
		if (size <= 0 || size > fileMaxBytes) {
			throw new ApiException(413, ErrorCode.FILE_TOO_LARGE, "file size exceeds upload limit");
		}
		long chunkSize = request.chunk_size() == null ? 0 : request.chunk_size();
		if (chunkSize == 0) {
			chunkSize = 5L * 1024 * 1024;
		}
		if (chunkSize < 1024 * 1024 || chunkSize > chunkMaxBytes) {
			throw ApiException.badRequest("chunk_size out of range (1MB ~ 10MB)");
		}
		Meta meta = service.init(UserContext.userId(), request.name(),
				request.mime_type() == null ? "" : request.mime_type(), size, request.folder_id(),
				request.md5() == null ? "" : request.md5(), chunkSize);
		Map<String, Object> data = Map.of("upload_id", meta.uploadId(), "chunk_size", meta.chunkSize(), "total_chunks",
				meta.totalChunks(), "remaining", meta.remaining());
		return ResponseEntity.status(HttpStatus.CREATED).body(Envelope.created(data));
	}

	@PostMapping("/api/v1/files/multipart/{upload_id}/parts")
	public Envelope<Map<String, Object>> part(@PathVariable("upload_id") String uploadId,
			@RequestParam(value = "index", required = false) String indexRaw,
			@RequestParam(value = "data", required = false) MultipartFile data) {
		String indexValue = indexRaw == null || indexRaw.isEmpty() ? "0" : indexRaw;
		int index;
		try {
			index = Integer.parseInt(indexValue);
		}
		catch (NumberFormatException e) {
			throw ApiException.badRequest("invalid index");
		}
		if (index < 0) {
			throw ApiException.badRequest("invalid index");
		}
		if (data == null || data.getSize() <= 0) {
			throw ApiException.badRequest("data part required");
		}
		if (data.getSize() > chunkMaxBytes) {
			throw new ApiException(413, ErrorCode.FILE_TOO_LARGE, "part exceeds chunk size limit");
		}
		List<Integer> received;
		try {
			received = service.uploadPart(uploadId, UserContext.userId(), index, data.getInputStream(), data.getSize());
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
		return Envelope.ok(Map.of("received", received));
	}

	@PostMapping("/api/v1/files/multipart/{upload_id}/complete")
	public Envelope<Map<String, Object>> complete(@PathVariable("upload_id") String uploadId) {
		Record record = service.complete(uploadId, UserContext.userId());
		return Envelope.ok(Responses.fileResponse(record));
	}

	@DeleteMapping("/api/v1/files/multipart/{upload_id}")
	public Envelope<Void> abort(@PathVariable("upload_id") String uploadId) {
		service.abort(uploadId, UserContext.userId());
		return Envelope.ok(null);
	}

}