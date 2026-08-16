// Package catalog contains framework-independent file and folder read use cases.
package catalog

import (
	"context"
	"strconv"
	"strings"
	"time"
)

type File struct {
	ID        int64  `json:"id"`
	Name      string `json:"name"`
	Size      int64  `json:"size"`
	MimeType  string `json:"mime_type"`
	MD5       string `json:"md5"`
	FolderID  *int64 `json:"folder_id"`
	OwnerID   int64  `json:"-"`
	CreatedAt string `json:"created_at"`
}

type Folder struct {
	ID        int64
	Name      string
	ParentID  *int64
	OwnerID   int64
	CreatedAt time.Time
}

type FileQuery interface {
	FindFile(context.Context, int64) (File, error)
	ListFiles(context.Context, int64, int, int) ([]File, int64, error)
	SearchFiles(context.Context, int64, string, int, int) ([]File, int64, error)
	ListFolderFiles(context.Context, int64, int64) ([]File, error)
}

type FolderQuery interface {
	FindFolder(context.Context, int64) (Folder, error)
	ListRootFolders(context.Context, int64) ([]Folder, error)
	ListChildFolders(context.Context, int64, int64) ([]Folder, error)
}

type FolderCommand interface {
	CreateFolder(context.Context, int64, *int64, string) (Folder, error)
	RenameFolder(context.Context, int64, int64, string) (bool, error)
	MoveFolder(context.Context, int64, int64, *int64) (bool, error)
	CollectChildIDs(context.Context, int64) ([]int64, error)
}

// FileCommand keeps SQL updates outside the application layer.
type FileCommand interface {
	NameTaken(context.Context, int64, *int64, string, int64) (bool, error)
	RenameFile(context.Context, int64, int64, string) (bool, error)
	MoveFile(context.Context, int64, int64, *int64, string) (bool, error)
}

type Service struct {
	files    FileQuery
	folders  FolderQuery
	commands FolderCommand
	fileCmd  FileCommand
}

func NewService(files FileQuery, folders FolderQuery) *Service {
	return &Service{files: files, folders: folders}
}

func NewServiceWithCommands(files FileQuery, folders FolderQuery, commands FolderCommand) *Service {
	return &Service{files: files, folders: folders, commands: commands}
}

func NewServiceWithMutations(files FileQuery, folders FolderQuery, foldersCmd FolderCommand, filesCmd FileCommand) *Service {
	return &Service{files: files, folders: folders, commands: foldersCmd, fileCmd: filesCmd}
}

type Page struct {
	Files []File `json:"files"`
	Total int64  `json:"total"`
	Page  int    `json:"page"`
}

type FolderView struct {
	ID        int64        `json:"id"`
	Name      string       `json:"name"`
	ParentID  *int64       `json:"parent_id"`
	CreatedAt string       `json:"created_at"`
	Children  []FolderView `json:"children"`
	Files     []File       `json:"files"`
}

func (s *Service) ListFiles(ctx context.Context, ownerID, page, pageSize int) (Page, error) {
	files, total, err := s.files.ListFiles(ctx, int64(ownerID), page, pageSize)
	return Page{Files: files, Total: total, Page: page}, err
}

func (s *Service) SearchFiles(ctx context.Context, ownerID int, query string, page, pageSize int) (Page, error) {
	files, total, err := s.files.SearchFiles(ctx, int64(ownerID), query, page, pageSize)
	return Page{Files: files, Total: total, Page: page}, err
}

func (s *Service) GetFile(ctx context.Context, ownerID, fileID int) (File, error) {
	file, err := s.files.FindFile(ctx, int64(fileID))
	if err == nil && file.OwnerID != int64(ownerID) {
		return File{}, ErrForbidden
	}
	return file, err
}

func (s *Service) Root(ctx context.Context, ownerID int) ([]FolderView, error) {
	folders, err := s.folders.ListRootFolders(ctx, int64(ownerID))
	if err != nil {
		return nil, err
	}
	return s.folderViews(ctx, int64(ownerID), folders)
}

func (s *Service) Tree(ctx context.Context, ownerID, folderID int) (FolderView, error) {
	folder, err := s.folders.FindFolder(ctx, int64(folderID))
	if err != nil {
		return FolderView{}, err
	}
	if folder.OwnerID != int64(ownerID) {
		return FolderView{}, ErrForbidden
	}
	views, err := s.folderViews(ctx, int64(ownerID), []Folder{folder})
	if err != nil {
		return FolderView{}, err
	}
	return views[0], nil
}

func (s *Service) CreateFolder(ctx context.Context, ownerID int, parentID *int64, name string) (Folder, error) {
	if strings.TrimSpace(name) == "" {
		return Folder{}, ErrNameRequired
	}
	parentID = normalizeParentID(parentID)
	if parentID != nil {
		parent, err := s.folders.FindFolder(ctx, *parentID)
		if err != nil {
			return Folder{}, err
		}
		if parent.OwnerID != int64(ownerID) {
			return Folder{}, ErrForbidden
		}
	}
	return s.commands.CreateFolder(ctx, int64(ownerID), parentID, name)
}

func (s *Service) RenameFolder(ctx context.Context, ownerID, folderID int, name string) error {
	if strings.TrimSpace(name) == "" {
		return ErrNameRequired
	}
	if _, err := s.ownedFolder(ctx, int64(ownerID), int64(folderID)); err != nil {
		return err
	}
	changed, err := s.commands.RenameFolder(ctx, int64(ownerID), int64(folderID), name)
	if err == nil && !changed {
		return ErrNotFound
	}
	return err
}

func (s *Service) MoveFolder(ctx context.Context, ownerID, folderID int, parentID *int64) error {
	parentID = normalizeParentID(parentID)
	folder, err := s.ownedFolder(ctx, int64(ownerID), int64(folderID))
	if err != nil {
		return err
	}
	if parentID != nil {
		parent, err := s.folders.FindFolder(ctx, *parentID)
		if err != nil {
			return err
		}
		if parent.OwnerID != int64(ownerID) {
			return ErrForbidden
		}
		if *parentID == folder.ID {
			return ErrFolderCycle
		}
		children, err := s.commands.CollectChildIDs(ctx, folder.ID)
		if err != nil {
			return err
		}
		for _, childID := range children {
			if childID == *parentID {
				return ErrFolderCycle
			}
		}
	}
	changed, err := s.commands.MoveFolder(ctx, int64(ownerID), int64(folderID), normalizeParentID(parentID))
	if err == nil && !changed {
		return ErrNotFound
	}
	return err
}

func (s *Service) RenameFile(ctx context.Context, ownerID, fileID int, name string) error {
	if strings.TrimSpace(name) == "" {
		return ErrNameRequired
	}
	file, err := s.ownedFile(ctx, int64(ownerID), int64(fileID))
	if err != nil {
		return err
	}
	unique, err := s.uniqueFileName(ctx, int64(ownerID), file.FolderID, name, file.ID)
	if err != nil {
		return err
	}
	changed, err := s.fileCmd.RenameFile(ctx, int64(ownerID), file.ID, unique)
	if err == nil && !changed {
		return ErrNotFound
	}
	return err
}

func (s *Service) MoveFile(ctx context.Context, ownerID, fileID int, folderID *int64) error {
	file, err := s.ownedFile(ctx, int64(ownerID), int64(fileID))
	if err != nil {
		return err
	}
	folderID = normalizeParentID(folderID)
	if folderID != nil {
		folder, err := s.folders.FindFolder(ctx, *folderID)
		if err != nil {
			return err
		}
		if folder.OwnerID != int64(ownerID) {
			return ErrForbidden
		}
	}
	unique, err := s.uniqueFileName(ctx, int64(ownerID), folderID, file.Name, file.ID)
	if err != nil {
		return err
	}
	changed, err := s.fileCmd.MoveFile(ctx, int64(ownerID), file.ID, folderID, unique)
	if err == nil && !changed {
		return ErrNotFound
	}
	return err
}

func (s *Service) ownedFile(ctx context.Context, ownerID, fileID int64) (File, error) {
	file, err := s.files.FindFile(ctx, fileID)
	if err == nil && file.OwnerID != ownerID {
		return File{}, ErrForbidden
	}
	return file, err
}

func (s *Service) uniqueFileName(ctx context.Context, ownerID int64, folderID *int64, name string, excludeID int64) (string, error) {
	taken, err := s.fileCmd.NameTaken(ctx, ownerID, folderID, name, excludeID)
	if err != nil || !taken {
		return name, err
	}
	dot := lastDot(name)
	stem, ext := name, ""
	if dot >= 0 {
		stem, ext = name[:dot], name[dot:]
	}
	start := 1
	if open := strings.LastIndex(stem, "("); open >= 0 && strings.HasSuffix(stem, ")") {
		if suffix, parseErr := strconv.Atoi(stem[open+1 : len(stem)-1]); parseErr == nil {
			stem, start = stem[:open], suffix+1
		}
	}
	for suffix := start; ; suffix++ {
		candidate := stem + "(" + strconv.Itoa(suffix) + ")" + ext
		taken, err = s.fileCmd.NameTaken(ctx, ownerID, folderID, candidate, excludeID)
		if err != nil || !taken {
			return candidate, err
		}
	}
}

func normalizeParentID(parentID *int64) *int64 {
	if parentID == nil || *parentID == 0 {
		return nil
	}
	return parentID
}

func (s *Service) ownedFolder(ctx context.Context, ownerID, folderID int64) (Folder, error) {
	folder, err := s.folders.FindFolder(ctx, folderID)
	if err == nil && folder.OwnerID != ownerID {
		return Folder{}, ErrForbidden
	}
	return folder, err
}

func (s *Service) folderViews(ctx context.Context, ownerID int64, folders []Folder) ([]FolderView, error) {
	views := make([]FolderView, 0, len(folders))
	for _, folder := range folders {
		children, err := s.folders.ListChildFolders(ctx, ownerID, folder.ID)
		if err != nil {
			return nil, err
		}
		files, err := s.files.ListFolderFiles(ctx, folder.ID, ownerID)
		if err != nil {
			return nil, err
		}
		views = append(views, FolderView{ID: folder.ID, Name: folder.Name, ParentID: folder.ParentID,
			CreatedAt: formatTime(folder.CreatedAt), Children: folderViewsShallow(children), Files: files})
	}
	return views, nil
}

func folderViewsShallow(folders []Folder) []FolderView {
	views := make([]FolderView, 0, len(folders))
	for _, folder := range folders {
		views = append(views, FolderView{ID: folder.ID, Name: folder.Name, ParentID: folder.ParentID,
			CreatedAt: formatTime(folder.CreatedAt), Children: []FolderView{}, Files: []File{}})
	}
	return views
}

func formatTime(value time.Time) string {
	if value.IsZero() {
		return ""
	}
	return value.UTC().Format("2006-01-02T15:04:05Z")
}

var ErrForbidden = &catalogError{"access denied"}
var ErrFolderCycle = &catalogError{"cannot move folder into its own descendant"}
var ErrNotFound = &catalogError{"resource not found"}
var ErrNameRequired = &catalogError{"name required"}

type catalogError struct{ message string }

func (e *catalogError) Error() string { return e.message }

func lastDot(name string) int {
	for i := len(name) - 1; i >= 0; i-- {
		if name[i] == '.' {
			return i
		}
	}
	return -1
}
