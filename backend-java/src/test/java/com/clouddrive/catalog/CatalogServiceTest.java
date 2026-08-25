package com.clouddrive.catalog;

import com.clouddrive.common.Errors;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CatalogServiceTest {

	private static Folder folder(long id, long owner, Long parent) {
		return new Folder(id, "folder" + id, parent, owner, LocalDateTime.now());
	}

	private static File file(long id, long owner) {
		return new File(id, "notes.md", 10, "text/markdown", "abc", null, owner, "");
	}

	private static class Memory implements FileQuery, FolderQuery, FolderCommand, FileCommand {

		java.util.Map<Long, File> files = new java.util.HashMap<>();

		java.util.Map<Long, Folder> folders = new java.util.HashMap<>();

		java.util.Map<String, Boolean> taken = new java.util.HashMap<>();

		String renamedTo;

		Long movedTo;

		String movedName;

		boolean changed = true;

		Long folderMoveTarget;

		boolean folderChanged = true;

		@Override
		public File findFile(long id) {
			File f = files.get(id);
			if (f == null) {
				throw new Errors.AccessDenied("access denied");
			}
			return f;
		}

		@Override
		public FileList listFiles(long ownerId, int page, int pageSize) {
			return new FileList(List.of(), 0);
		}

		@Override
		public FileList searchFiles(long ownerId, String term, int page, int pageSize) {
			return new FileList(List.of(), 0);
		}

		@Override
		public List<File> listFolderFiles(long ownerId, long folderId) {
			return List.of();
		}

		@Override
		public Folder findFolder(long id) {
			Folder f = folders.get(id);
			if (f == null) {
				throw new Errors.AccessDenied("access denied");
			}
			return f;
		}

		@Override
		public List<Folder> listRootFolders(long ownerId) {
			return List.of();
		}

		@Override
		public List<Folder> listChildFolders(long ownerId, long parentId) {
			return List.of();
		}

		@Override
		public Folder createFolder(long ownerId, Long parentId, String name) {
			return folder(99, ownerId, parentId);
		}

		@Override
		public boolean renameFolder(long ownerId, long folderId, String name) {
			return folderChanged;
		}

		@Override
		public boolean moveFolder(long ownerId, long folderId, Long parentId) {
			folderMoveTarget = parentId;
			return folderChanged;
		}

		@Override
		public List<Long> collectChildIds(long folderId) {
			List<Long> ids = new ArrayList<>();
			ids.add(folderId);
			boolean added = true;
			while (added) {
				added = false;
				for (Folder f : folders.values()) {
					if (f.parentId() != null && ids.contains(f.parentId()) && !ids.contains(f.id())) {
						ids.add(f.id());
						added = true;
					}
				}
			}
			return ids;
		}

		@Override
		public boolean nameTaken(long ownerId, Long folderId, String name, long excludeId) {
			return Boolean.TRUE.equals(taken.get(name));
		}

		@Override
		public boolean renameFile(long ownerId, long fileId, String name) {
			renamedTo = name;
			return changed;
		}

		@Override
		public boolean moveFile(long ownerId, long fileId, Long folderId, String name) {
			movedTo = folderId;
			movedName = name;
			return changed;
		}

	}

	@Test
	void renameFileGeneratesIncrementedSuffix() {
		Memory store = new Memory();
		store.files.put(1L, file(1, 7));
		store.taken.put("report(1).txt", true);
		store.taken.put("report(2).txt", true);
		CatalogService service = new CatalogService(store, store, store, store);
		service.renameFile(7, 1, "report(1).txt");
		assertEquals("report(3).txt", store.renamedTo);
	}

	@Test
	void moveFileChecksTargetOwnerAndNormalizesRoot() {
		Memory store = new Memory();
		store.files.put(1L, file(1, 7));
		store.folders.put(2L, folder(2, 8, null));
		CatalogService service = new CatalogService(store, store, store, store);
		assertThrows(Errors.AccessDenied.class, () -> service.moveFile(7, 1, 2L));
		service.moveFile(7, 1, 0L);
		assertEquals(null, store.movedTo);
		assertEquals("notes.md", store.movedName);
	}

	@Test
	void renameFileReturnsNotFoundWhenConcurrentMutationDeletesRecord() {
		Memory store = new Memory();
		store.files.put(1L, file(1, 7));
		store.changed = false;
		CatalogService service = new CatalogService(store, store, store, store);
		assertThrows(Errors.NotFound.class, () -> service.renameFile(7, 1, "renamed.md"));
	}

	@Test
	void folderAndFileNamesCannotBeBlank() {
		Memory store = new Memory();
		store.files.put(1L, file(1, 7));
		CatalogService service = new CatalogService(store, store, store, store);
		assertThrows(Errors.NameRequired.class, () -> service.createFolder(7, null, "   "));
		assertThrows(Errors.NameRequired.class, () -> service.renameFolder(7, 1, "\t"));
		assertThrows(Errors.NameRequired.class, () -> service.renameFile(7, 1, " "));
	}

	@Test
	void moveFolderCycleDetected() {
		Memory store = new Memory();
		store.folders.put(1L, folder(1, 7, null));
		store.folders.put(2L, folder(2, 7, 1L));
		CatalogService service = new CatalogService(store, store, store, store);
		assertThrows(Errors.FolderCycle.class, () -> service.moveFolder(7, 1, 2L));
	}

	@Test
	void moveFolderIntoItselfDetected() {
		Memory store = new Memory();
		store.folders.put(1L, folder(1, 7, null));
		CatalogService service = new CatalogService(store, store, store, store);
		assertThrows(Errors.FolderCycle.class, () -> service.moveFolder(7, 1, 1L));
	}

	@Test
	void moveFolderCapturesNormalizedTarget() {
		Memory store = new Memory();
		store.folders.put(1L, folder(1, 7, null));
		store.folders.put(2L, folder(2, 7, null));
		CatalogService service = new CatalogService(store, store, store, store);
		service.moveFolder(7, 1, 2L);
		assertEquals(2L, store.folderMoveTarget);
		service.moveFolder(7, 1, 0L);
		assertEquals(null, store.folderMoveTarget);
	}

	@Test
	void getFileDeniesForeignOwner() {
		Memory store = new Memory();
		store.files.put(1L, file(1, 8));
		CatalogService service = new CatalogService(store, store, store, store);
		assertThrows(Errors.AccessDenied.class, () -> service.getFile(7, 1));
	}

}