package com.clouddrive.controller;

import com.clouddrive.common.ApiException;
import com.clouddrive.common.Envelope;
import com.clouddrive.common.ErrorCode;
import com.clouddrive.file.Download;
import com.clouddrive.file.FileService;
import com.clouddrive.file.Record;
import com.clouddrive.web.Downloader;
import com.clouddrive.web.Responses;
import com.clouddrive.web.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * 文件控制器，对应 Go httpapi/files.go（含上传、下载、删除、秒传、文本、内容读取）。
 */
@RestController
public class FileController {

	private final FileService service;

	private final long directMaxBytes;

	private final ObjectMapper mapper;

	public FileController(FileService service, com.clouddrive.config.AppProperties properties, ObjectMapper mapper) {
		this.service = service;
		this.directMaxBytes = properties.getUploadDirectMaxBytes();
		this.mapper = mapper;
	}

	@PostMapping("/api/v1/files/upload")
	public ResponseEntity<Envelope<Map<String, Object>>> upload(
			@RequestParam(value = "file", required = false) MultipartFile file,
			@RequestParam(value = "folder_id", required = false) String folderIdValue) {
		if (file == null || file.isEmpty()) {
			throw ApiException.badRequest("file is required");
		}
		if (file.getSize() > directMaxBytes) {
			throw new ApiException(413, ErrorCode.FILE_TOO_LARGE,
					"file too large, use multipart upload for files over 50MB");
		}
		byte[] data;
		try {
			data = file.getBytes();
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
		if (data.length > directMaxBytes) {
			throw new ApiException(413, ErrorCode.FILE_TOO_LARGE,
					"file too large, use multipart upload for files over 50MB");
		}
		if (data.length == 0) {
			throw ApiException.badRequest("file is required");
		}
		Long folderId = optionalId(folderIdValue);
		String mime = file.getContentType();
		if (mime == null || mime.isEmpty()) {
			mime = "application/octet-stream";
		}
		String name = baseName(file.getOriginalFilename());
		Record record = service.upload(UserContext.userId(), folderId, name, mime, data);
		return ResponseEntity.status(HttpStatus.CREATED).body(Envelope.created(Responses.fileResponse(record)));
	}

	@GetMapping("/api/v1/files/{id}/download")
	public void download(@PathVariable("id") String idRaw, HttpServletRequest request, HttpServletResponse response) {
		long id = fileId(idRaw);
		Download download = service.download(UserContext.userId(), id);
		Downloader.write(response, request, download.record(), download.body(),
				(offset, length) -> service.downloadRange(UserContext.userId(), id, offset, length));
	}

	@DeleteMapping("/api/v1/files/{id}")
	public Envelope<Void> delete(@PathVariable("id") String idRaw) {
		service.delete(UserContext.userId(), fileId(idRaw));
		return Envelope.ok(null);
	}

	@DeleteMapping("/api/v1/folders/{id}")
	public Envelope<Void> deleteFolder(@PathVariable("id") String idRaw) {
		service.deleteFolder(UserContext.userId(), fileId(idRaw));
		return Envelope.ok(null);
	}

	public record ChecksumRequest(String md5, String name, Long size, Long folder_id) {
	}

	@PostMapping("/api/v1/files/checksum")
	public Envelope<Map<String, Object>> checksum(@RequestBody ChecksumRequest request) {
		if (request.md5() == null || request.md5().isEmpty() || request.name() == null || request.name().isEmpty()
				|| request.size() == null || request.size() <= 0) {
			throw ApiException.badRequest("validation failed");
		}
		FileService.InstantResult result = service.checksumInstant(UserContext.userId(), request.md5(), request.name(),
				request.size(), request.folder_id());
		if (!result.instant()) {
			return Envelope.ok(Map.of("instant", false));
		}
		return Envelope.ok(Map.of("instant", true, "file", (Object) Responses.fileResponse(result.record())));
	}

	public record TextRequest(String name, String content, Long folder_id) {
	}

	@PostMapping("/api/v1/files/text")
	public ResponseEntity<Envelope<Map<String, Object>>> createTextFile(HttpServletRequest request) throws IOException {
		requireAgent();
		TextRequest req;
		try {
			req = mapper.readValue(request.getInputStream(), TextRequest.class);
		}
		catch (IOException e) {
			throw ApiException.badRequest("invalid request body");
		}
		if (req.name() == null || req.name().isEmpty() || req.content() == null || req.content().isEmpty()) {
			throw ApiException.badRequest("validation failed");
		}
		if (req.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > directMaxBytes) {
			throw new ApiException(413, ErrorCode.FILE_TOO_LARGE, "content too large");
		}
		Record record = service.createTextFile(UserContext.userId(), req.name(), req.content(), req.folder_id());
		return ResponseEntity.status(HttpStatus.CREATED).body(Envelope.created(Responses.fileResponse(record)));
	}

	public record ContentRequest(String content) {
	}

	@PutMapping("/api/v1/files/{id}/content")
	public Envelope<Map<String, Object>> overwriteContent(@PathVariable("id") String idRaw, HttpServletRequest request)
			throws IOException {
		requireAgent();
		ContentRequest req;
		try {
			req = mapper.readValue(request.getInputStream(), ContentRequest.class);
		}
		catch (IOException e) {
			throw ApiException.badRequest("invalid request body");
		}
		if (req.content() == null || req.content().isEmpty()) {
			throw ApiException.badRequest("validation failed");
		}
		Record record = service.overwriteContent(UserContext.userId(), fileId(idRaw), req.content());
		return Envelope.ok(Responses.fileResponse(record));
	}

	@GetMapping("/api/v1/files/{id}/content")
	public Envelope<com.clouddrive.file.ContentView> readContent(@PathVariable("id") String idRaw,
			@RequestParam(value = "offset", required = false) String offsetRaw,
			@RequestParam(value = "limit", required = false) String limitRaw) {
		long id = fileId(idRaw);
		int offset = 1;
		if (offsetRaw != null && !offsetRaw.isEmpty()) {
			try {
				offset = Integer.parseInt(offsetRaw);
			}
			catch (NumberFormatException e) {
				throw ApiException.badRequest("invalid offset");
			}
		}
		Integer limit = null;
		if (limitRaw != null && !limitRaw.isEmpty()) {
			try {
				limit = Integer.parseInt(limitRaw);
			}
			catch (NumberFormatException e) {
				throw ApiException.badRequest("invalid limit");
			}
		}
		return Envelope.ok(service.readContent(UserContext.userId(), id, offset, limit));
	}

	private static long fileId(String raw) {
		try {
			long id = Long.parseLong(raw);
			if (id <= 0) {
				throw new NumberFormatException();
			}
			return id;
		}
		catch (NumberFormatException e) {
			throw ApiException.badRequest("invalid file id");
		}
	}

	private static void requireAgent() {
		if (!UserContext.isAgent()) {
			throw new ApiException(403, ErrorCode.FORBIDDEN, "agent only");
		}
	}

	private static Long optionalId(String value) {
		if (value == null || value.isEmpty()) {
			return null;
		}
		try {
			long id = Long.parseLong(value);
			if (id < 0) {
				throw new NumberFormatException();
			}
			return id;
		}
		catch (NumberFormatException e) {
			throw ApiException.badRequest("invalid folder id");
		}
	}

	private static String baseName(String value) {
		if (value == null || value.isEmpty()) {
			return "";
		}
		int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
		return slash >= 0 ? value.substring(slash + 1) : value;
	}

}