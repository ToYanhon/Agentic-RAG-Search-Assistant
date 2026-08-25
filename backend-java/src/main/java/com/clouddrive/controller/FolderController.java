package com.clouddrive.controller;

import com.clouddrive.catalog.CatalogService;
import com.clouddrive.catalog.File;
import com.clouddrive.catalog.Folder;
import com.clouddrive.catalog.FolderView;
import com.clouddrive.catalog.Page;
import com.clouddrive.common.ApiException;
import com.clouddrive.common.Envelope;
import com.clouddrive.web.Responses;
import com.clouddrive.web.UserContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 目录控制器，对应 Go httpapi/catalog.go（文件列表/搜索/重命名/移动 + 文件夹树）。
 */
@RestController
public class FolderController {

	private final CatalogService catalog;

	public FolderController(CatalogService catalog) {
		this.catalog = catalog;
	}

	@GetMapping("/api/v1/files")
	public Envelope<Page> listFiles(@RequestParam(value = "page", required = false) String pageRaw,
			@RequestParam(value = "page_size", required = false) String pageSizeRaw) {
		int[] paging = Responses.pagination(pageRaw, pageSizeRaw);
		return Envelope.ok(catalog.listFiles(UserContext.userId(), paging[0], paging[1]));
	}

	@GetMapping("/api/v1/files/search")
	public Envelope<Page> searchFiles(@RequestParam(value = "q", required = false) String q,
			@RequestParam(value = "page", required = false) String pageRaw,
			@RequestParam(value = "page_size", required = false) String pageSizeRaw) {
		if (q == null || q.isEmpty()) {
			throw ApiException.badRequest("query required");
		}
		int[] paging = Responses.pagination(pageRaw, pageSizeRaw);
		return Envelope.ok(catalog.searchFiles(UserContext.userId(), q, paging[0], paging[1]));
	}

	@GetMapping("/api/v1/files/{id}")
	public Envelope<File> getFile(@PathVariable("id") String idRaw) {
		return Envelope.ok(catalog.getFile(UserContext.userId(), fileId(idRaw)));
	}

	public record RenameFileRequest(String name) {
	}

	@PutMapping("/api/v1/files/{id}")
	public Envelope<Void> renameFile(@PathVariable("id") String idRaw, @RequestBody RenameFileRequest request) {
		long id = fileId(idRaw);
		if (request.name() == null || request.name().isEmpty()) {
			throw ApiException.badRequest("name required");
		}
		catalog.renameFile(UserContext.userId(), id, request.name());
		return Envelope.ok(null);
	}

	public record MoveFileRequest(Long target_folder_id) {
	}

	@PutMapping("/api/v1/files/{id}/move")
	public Envelope<Void> moveFile(@PathVariable("id") String idRaw, @RequestBody MoveFileRequest request) {
		catalog.moveFile(UserContext.userId(), fileId(idRaw), request.target_folder_id());
		return Envelope.ok(null);
	}

	@GetMapping("/api/v1/folders/root")
	public Envelope<List<FolderView>> rootFolders() {
		return Envelope.ok(catalog.root(UserContext.userId()));
	}

	@GetMapping("/api/v1/folders/{id}")
	public Envelope<FolderView> folderTree(@PathVariable("id") String idRaw) {
		return Envelope.ok(catalog.tree(UserContext.userId(), folderId(idRaw)));
	}

	public record FolderRequest(String name, Long parent_id) {
	}

	@PostMapping("/api/v1/folders")
	public ResponseEntity<Envelope<Folder>> createFolder(@RequestBody FolderRequest request) {
		if (request.name() == null || request.name().isEmpty()) {
			throw ApiException.badRequest("name required");
		}
		Folder folder = catalog.createFolder(UserContext.userId(), request.parent_id(), request.name());
		return ResponseEntity.status(HttpStatus.CREATED).body(Envelope.created(folder));
	}

	@PutMapping("/api/v1/folders/{id}")
	public Envelope<Void> renameFolder(@PathVariable("id") String idRaw, @RequestBody FolderRequest request) {
		long id = folderId(idRaw);
		if (request.name() == null || request.name().isEmpty()) {
			throw ApiException.badRequest("name required");
		}
		catalog.renameFolder(UserContext.userId(), id, request.name());
		return Envelope.ok(null);
	}

	public record MoveFolderRequest(Long target_parent_id) {
	}

	@PutMapping("/api/v1/folders/{id}/move")
	public Envelope<Void> moveFolder(@PathVariable("id") String idRaw, @RequestBody MoveFolderRequest request) {
		catalog.moveFolder(UserContext.userId(), folderId(idRaw), request.target_parent_id());
		return Envelope.ok(null);
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

	private static long folderId(String raw) {
		try {
			long id = Long.parseLong(raw);
			if (id <= 0) {
				throw new NumberFormatException();
			}
			return id;
		}
		catch (NumberFormatException e) {
			throw ApiException.badRequest("invalid folder id");
		}
	}

}