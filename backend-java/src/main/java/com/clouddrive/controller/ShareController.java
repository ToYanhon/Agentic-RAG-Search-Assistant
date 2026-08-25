package com.clouddrive.controller;

import com.clouddrive.common.ApiException;
import com.clouddrive.common.Envelope;
import com.clouddrive.common.ErrorCode;
import com.clouddrive.common.Errors;
import com.clouddrive.file.Download;
import com.clouddrive.file.Record;
import com.clouddrive.share.ShareService;
import com.clouddrive.web.Downloader;
import com.clouddrive.web.Responses;
import com.clouddrive.web.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 分享控制器，对应 Go httpapi/shares.go。/s/{token} 为公开访问（不经过 AuthFilter）。
 */
@RestController
public class ShareController {

	private final ShareService service;

	public ShareController(ShareService service) {
		this.service = service;
	}

	public record CreateShareRequest(Long file_id, Integer expire_hours) {
	}

	@PostMapping("/api/v1/shares")
	public ResponseEntity<Envelope<Map<String, Object>>> createShare(@RequestBody CreateShareRequest request) {
		if (request.file_id() == null || request.file_id() <= 0) {
			throw ApiException.badRequest("validation failed");
		}
		com.clouddrive.share.Record created = shareOrNotFound(
				() -> service.create(UserContext.userId(), request.file_id(), request.expire_hours()));
		Map<String, Object> data = Map.of("id", created.id(), "token", created.token(), "url", "/s/" + created.token());
		return ResponseEntity.status(HttpStatus.CREATED).body(Envelope.created(data));
	}

	@DeleteMapping("/api/v1/shares/{id}")
	public Envelope<Void> deleteShare(@PathVariable("id") String idRaw) {
		long id;
		try {
			id = Long.parseLong(idRaw);
		}
		catch (NumberFormatException e) {
			throw ApiException.badRequest("invalid share id");
		}
		if (id <= 0) {
			throw ApiException.badRequest("invalid share id");
		}
		shareOrNotFound(() -> {
			service.revoke(UserContext.userId(), id);
			return null;
		});
		return Envelope.ok(null);
	}

	@GetMapping("/s/{token}")
	public Envelope<Map<String, Object>> publicShare(@PathVariable("token") String token) {
		Record record = shareOrNotFound(() -> service.access(token));
		return Envelope.ok(Responses.fileResponse(record));
	}

	@GetMapping("/s/{token}/download")
	public void publicShareDownload(@PathVariable("token") String token, HttpServletRequest request,
			HttpServletResponse response) {
		Download download = shareOrNotFound(() -> service.download(token));
		Downloader.write(response, request, download.record(), download.body(),
				(offset, length) -> service.downloadRange(token, offset, length));
	}

	private static <T> T shareOrNotFound(Supplier<T> supplier) {
		try {
			return supplier.get();
		}
		catch (Errors.ShareNotFound | Errors.NotFound e) {
			throw new ApiException(404, ErrorCode.NOT_FOUND, "share not found");
		}
	}

}