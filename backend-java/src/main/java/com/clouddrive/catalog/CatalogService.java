package com.clouddrive.catalog;

import com.clouddrive.common.Errors;
import com.clouddrive.common.TimeUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 目录用例，对应 Go catalog.Service。
 */
@org.springframework.stereotype.Service
public class CatalogService {

	private final FileQuery files;

	private final FolderQuery folders;

	private final FolderCommand commands;

	private final FileCommand fileCmd;

	public CatalogService(FileQuery files, FolderQuery folders, FolderCommand commands, FileCommand fileCmd) {
		this.files = files;
		this.folders = folders;
		this.commands = commands;
		this.fileCmd = fileCmd;
	}

	public Page listFiles(long ownerId, int page, int pageSize) {
		FileQuery.FileList result = files.listFiles(ownerId, page, pageSize);
		return new Page(result.files(), result.total(), page);
	}

	public Page searchFiles(long ownerId, String query, int page, int pageSize) {
		FileQuery.FileList result = files.searchFiles(ownerId, query, page, pageSize);
		return new Page(result.files(), result.total(), page);
	}

	public File getFile(long ownerId, long fileId) {
		File file = files.findFile(fileId);
		if (file.ownerId() != ownerId) {
			throw new Errors.AccessDenied("access denied");
		}
		return file;
	}

	public List<FolderView> root(long ownerId) {
		return folderViews(ownerId, folders.listRootFolders(ownerId));
	}

	public FolderView tree(long ownerId, long folderId) {
		Folder folder = folders.findFolder(folderId);
		if (folder.ownerId() != ownerId) {
			throw new Errors.AccessDenied("access denied");
		}
		return folderViews(ownerId, List.of(folder)).get(0);
	}

	public Folder createFolder(long ownerId, Long parentId, String name) {
		if (name == null || name.trim().isEmpty()) {
			throw new Errors.NameRequired("name required");
		}
		Long normalized = normalizeParentId(parentId);
		if (normalized != null) {
			Folder parent = folders.findFolder(normalized);
			if (parent.ownerId() != ownerId) {
				throw new Errors.AccessDenied("access denied");
			}
		}
		return commands.createFolder(ownerId, normalized, name);
	}

	public void renameFolder(long ownerId, long folderId, String name) {
		if (name == null || name.trim().isEmpty()) {
			throw new Errors.NameRequired("name required");
		}
		ownedFolder(ownerId, folderId);
		if (!commands.renameFolder(ownerId, folderId, name)) {
			throw new Errors.NotFound("resource not found");
		}
	}

	public void moveFolder(long ownerId, long folderId, Long parentId) {
		Long normalized = normalizeParentId(parentId);
		Folder folder = ownedFolder(ownerId, folderId);
		if (normalized != null) {
			Folder parent = folders.findFolder(normalized);
			if (parent.ownerId() != ownerId) {
				throw new Errors.AccessDenied("access denied");
			}
			if (normalized.equals(folder.id())) {
				throw new Errors.FolderCycle("cannot move folder into its own descendant");
			}
			for (Long childId : commands.collectChildIds(folder.id())) {
				if (childId.equals(normalized)) {
					throw new Errors.FolderCycle("cannot move folder into its own descendant");
				}
			}
		}
		if (!commands.moveFolder(ownerId, folderId, normalized)) {
			throw new Errors.NotFound("resource not found");
		}
	}

	public void renameFile(long ownerId, long fileId, String name) {
		if (name == null || name.trim().isEmpty()) {
			throw new Errors.NameRequired("name required");
		}
		File file = ownedFile(ownerId, fileId);
		String unique = uniqueFileName(ownerId, file.folderId(), name, file.id());
		if (!fileCmd.renameFile(ownerId, file.id(), unique)) {
			throw new Errors.NotFound("resource not found");
		}
	}

	public void moveFile(long ownerId, long fileId, Long folderId) {
		File file = ownedFile(ownerId, fileId);
		Long normalized = normalizeParentId(folderId);
		if (normalized != null) {
			Folder folder = folders.findFolder(normalized);
			if (folder.ownerId() != ownerId) {
				throw new Errors.AccessDenied("access denied");
			}
		}
		String unique = uniqueFileName(ownerId, normalized, file.name(), file.id());
		if (!fileCmd.moveFile(ownerId, file.id(), normalized, unique)) {
			throw new Errors.NotFound("resource not found");
		}
	}

	private File ownedFile(long ownerId, long fileId) {
		File file = files.findFile(fileId);
		if (file.ownerId() != ownerId) {
			throw new Errors.AccessDenied("access denied");
		}
		return file;
	}

	private Folder ownedFolder(long ownerId, long folderId) {
		Folder folder = folders.findFolder(folderId);
		if (folder.ownerId() != ownerId) {
			throw new Errors.AccessDenied("access denied");
		}
		return folder;
	}

	private String uniqueFileName(long ownerId, Long folderId, String name, long excludeId) {
		boolean taken = fileCmd.nameTaken(ownerId, folderId, name, excludeId);
		if (!taken) {
			return name;
		}
		int dot = lastDot(name);
		String stem = name;
		String ext = "";
		if (dot >= 0) {
			stem = name.substring(0, dot);
			ext = name.substring(dot);
		}
		int start = 1;
		int open = stem.lastIndexOf('(');
		if (open >= 0 && stem.endsWith(")")) {
			try {
				int suffix = Integer.parseInt(stem.substring(open + 1, stem.length() - 1));
				stem = stem.substring(0, open);
				start = suffix + 1;
			}
			catch (NumberFormatException ignored) {
				// 非 (N) 后缀，保持原样
			}
		}
		for (int suffix = start;; suffix++) {
			String candidate = stem + "(" + suffix + ")" + ext;
			if (!fileCmd.nameTaken(ownerId, folderId, candidate, excludeId)) {
				return candidate;
			}
		}
	}

	private List<FolderView> folderViews(long ownerId, List<Folder> folders) {
		List<FolderView> views = new ArrayList<>(folders.size());
		for (Folder folder : folders) {
			List<Folder> children = this.folders.listChildFolders(ownerId, folder.id());
			List<File> files = this.files.listFolderFiles(ownerId, folder.id());
			views.add(new FolderView(folder.id(), folder.name(), folder.parentId(), TimeUtil.format(folder.createdAt()),
					folderViewsShallow(children), files));
		}
		return views;
	}

	private static List<FolderView> folderViewsShallow(List<Folder> folders) {
		List<FolderView> views = new ArrayList<>(folders.size());
		for (Folder folder : folders) {
			views.add(new FolderView(folder.id(), folder.name(), folder.parentId(), TimeUtil.format(folder.createdAt()),
					List.of(), List.of()));
		}
		return views;
	}

	private static Long normalizeParentId(Long parentId) {
		if (parentId == null || parentId == 0) {
			return null;
		}
		return parentId;
	}

	private static int lastDot(String name) {
		return name.lastIndexOf('.');
	}

}