package com.clouddrive.repository;

import com.clouddrive.catalog.File;
import com.clouddrive.catalog.FileCommand;
import com.clouddrive.catalog.FileQuery;
import com.clouddrive.catalog.Folder;
import com.clouddrive.catalog.FolderCommand;
import com.clouddrive.catalog.FolderQuery;
import com.clouddrive.common.Errors;
import com.clouddrive.common.TimeUtil;
import com.clouddrive.repository.CatalogJpa.FileRow;
import com.clouddrive.repository.CatalogJpa.FolderRow;
import com.clouddrive.repository.entity.FolderEntity;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 目录 MySQL 实现，对应 Go db.CatalogQuery（实现 catalog 的四个端口）。
 */
@Repository
public class DbCatalogRepository implements FileQuery, FolderQuery, FolderCommand, FileCommand {

	private final CatalogJpa jpa;

	public DbCatalogRepository(CatalogJpa jpa) {
		this.jpa = jpa;
	}

	@Override
	public File findFile(long id) {
		return jpa.findFile(id)
			.map(DbCatalogRepository::toFile)
			.orElseThrow(() -> new Errors.NotFound("resource not found"));
	}

	@Override
	public FileList listFiles(long ownerId, int page, int pageSize) {
		long total = jpa.countFiles(ownerId);
		List<FileRow> rows = jpa.listFiles(ownerId, (page - 1) * pageSize, pageSize);
		return new FileList(rows.stream().map(DbCatalogRepository::toFile).toList(), total);
	}

	@Override
	public FileList searchFiles(long ownerId, String term, int page, int pageSize) {
		String pattern = "%" + term + "%";
		long total = jpa.countSearch(ownerId, pattern);
		List<FileRow> rows = jpa.searchFiles(ownerId, pattern, (page - 1) * pageSize, pageSize);
		return new FileList(rows.stream().map(DbCatalogRepository::toFile).toList(), total);
	}

	@Override
	public List<File> listFolderFiles(long ownerId, long folderId) {
		return jpa.listFolderFiles(folderId, ownerId, 0, 100000).stream().map(DbCatalogRepository::toFile).toList();
	}

	@Override
	public Folder findFolder(long id) {
		return jpa.findFolder(id)
			.map(DbCatalogRepository::toFolder)
			.orElseThrow(() -> new Errors.NotFound("resource not found"));
	}

	@Override
	public List<Folder> listRootFolders(long ownerId) {
		return jpa.listRootFolders(ownerId).stream().map(DbCatalogRepository::toFolder).toList();
	}

	@Override
	public List<Folder> listChildFolders(long ownerId, long parentId) {
		return jpa.listChildFolders(ownerId, parentId).stream().map(DbCatalogRepository::toFolder).toList();
	}

	@Override
	public Folder createFolder(long ownerId, Long parentId, String name) {
		FolderEntity entity = new FolderEntity();
		entity.setName(name);
		entity.setParentId(parentId);
		entity.setOwnerId(ownerId);
		entity.setCreatedAt(LocalDateTime.now());
		entity.setUpdatedAt(LocalDateTime.now());
		FolderEntity saved = jpa.save(entity);
		return new Folder(saved.getId(), saved.getName(), saved.getParentId(), saved.getOwnerId(),
				saved.getCreatedAt());
	}

	@Override
	public boolean renameFolder(long ownerId, long folderId, String name) {
		return jpa.renameFolder(folderId, name, ownerId) > 0;
	}

	@Override
	public boolean moveFolder(long ownerId, long folderId, Long parentId) {
		return jpa.moveFolder(folderId, ownerId, parentId) > 0;
	}

	@Override
	public List<Long> collectChildIds(long folderId) {
		return jpa.collectChildIds(folderId);
	}

	@Override
	public boolean nameTaken(long ownerId, Long folderId, String name, long excludeId) {
		return jpa.countNameTaken(ownerId, name, folderId, excludeId) > 0;
	}

	@Override
	public boolean renameFile(long ownerId, long fileId, String name) {
		return jpa.renameFile(fileId, name, ownerId) > 0;
	}

	@Override
	public boolean moveFile(long ownerId, long fileId, Long folderId, String name) {
		return jpa.moveFile(fileId, ownerId, folderId, name) > 0;
	}

	private static File toFile(FileRow row) {
		return new File(row.getId(), row.getName(), row.getSize(), row.getMimeType(), row.getMd5(), row.getFolderId(),
				row.getOwnerId(), TimeUtil.format(row.getCreatedAt()));
	}

	private static Folder toFolder(FolderRow row) {
		return new Folder(row.getId(), row.getName(), row.getParentId(), row.getOwnerId(), row.getCreatedAt());
	}

}