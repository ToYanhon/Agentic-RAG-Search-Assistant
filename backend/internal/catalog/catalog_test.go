package catalog

import (
	"context"
	"testing"
)

type memoryCatalog struct {
	files     map[int64]File
	folders   map[int64]Folder
	taken     map[string]bool
	renamedTo string
	movedTo   *int64
	movedName string
	changed   bool
}

func (m *memoryCatalog) FindFile(_ context.Context, id int64) (File, error) {
	file, ok := m.files[id]
	if !ok {
		return File{}, ErrForbidden
	}
	return file, nil
}

func (m *memoryCatalog) ListFiles(context.Context, int64, int, int) ([]File, int64, error) {
	return nil, 0, nil
}
func (m *memoryCatalog) SearchFiles(context.Context, int64, string, int, int) ([]File, int64, error) {
	return nil, 0, nil
}
func (m *memoryCatalog) ListFolderFiles(context.Context, int64, int64) ([]File, error) {
	return nil, nil
}
func (m *memoryCatalog) FindFolder(_ context.Context, id int64) (Folder, error) {
	folder, ok := m.folders[id]
	if !ok {
		return Folder{}, ErrForbidden
	}
	return folder, nil
}
func (m *memoryCatalog) ListRootFolders(context.Context, int64) ([]Folder, error) { return nil, nil }
func (m *memoryCatalog) ListChildFolders(context.Context, int64, int64) ([]Folder, error) {
	return nil, nil
}
func (m *memoryCatalog) CreateFolder(context.Context, int64, *int64, string) (Folder, error) {
	return Folder{}, nil
}
func (m *memoryCatalog) RenameFolder(context.Context, int64, int64, string) (bool, error) {
	return m.changed, nil
}
func (m *memoryCatalog) MoveFolder(context.Context, int64, int64, *int64) (bool, error) {
	return m.changed, nil
}
func (m *memoryCatalog) CollectChildIDs(context.Context, int64) ([]int64, error) { return nil, nil }
func (m *memoryCatalog) NameTaken(_ context.Context, _ int64, _ *int64, name string, _ int64) (bool, error) {
	return m.taken[name], nil
}
func (m *memoryCatalog) RenameFile(_ context.Context, _ int64, _ int64, name string) (bool, error) {
	m.renamedTo = name
	return m.changed, nil
}
func (m *memoryCatalog) MoveFile(_ context.Context, _ int64, _ int64, folderID *int64, name string) (bool, error) {
	m.movedTo, m.movedName = folderID, name
	return m.changed, nil
}

func TestRenameFileGeneratesIncrementedSuffix(t *testing.T) {
	store := &memoryCatalog{
		files:   map[int64]File{1: {ID: 1, OwnerID: 7, Name: "report(1).txt"}},
		taken:   map[string]bool{"report(1).txt": true, "report(2).txt": true},
		changed: true,
	}
	service := NewServiceWithMutations(store, store, store, store)
	if err := service.RenameFile(context.Background(), 7, 1, "report(1).txt"); err != nil {
		t.Fatal(err)
	}
	if store.renamedTo != "report(3).txt" {
		t.Fatalf("renamed to %q", store.renamedTo)
	}
}

func TestMoveFileChecksTargetOwnerAndNormalizesRoot(t *testing.T) {
	store := &memoryCatalog{
		files:   map[int64]File{1: {ID: 1, OwnerID: 7, Name: "notes.md"}},
		folders: map[int64]Folder{2: {ID: 2, OwnerID: 8}},
		taken:   map[string]bool{},
		changed: true,
	}
	service := NewServiceWithMutations(store, store, store, store)
	otherOwner := int64(2)
	if err := service.MoveFile(context.Background(), 7, 1, &otherOwner); err != ErrForbidden {
		t.Fatalf("foreign target error = %v", err)
	}
	root := int64(0)
	if err := service.MoveFile(context.Background(), 7, 1, &root); err != nil {
		t.Fatal(err)
	}
	if store.movedTo != nil || store.movedName != "notes.md" {
		t.Fatalf("root move = folder:%v name:%q", store.movedTo, store.movedName)
	}
}

func TestRenameFileReturnsNotFoundWhenConcurrentMutationDeletesRecord(t *testing.T) {
	store := &memoryCatalog{files: map[int64]File{1: {ID: 1, OwnerID: 7, Name: "notes.md"}}, taken: map[string]bool{}}
	service := NewServiceWithMutations(store, store, store, store)
	if err := service.RenameFile(context.Background(), 7, 1, "renamed.md"); err != ErrNotFound {
		t.Fatalf("error = %v", err)
	}
}

func TestFolderAndFileNamesCannotBeBlank(t *testing.T) {
	store := &memoryCatalog{files: map[int64]File{1: {ID: 1, OwnerID: 7}}, folders: map[int64]Folder{}}
	service := NewServiceWithMutations(store, store, store, store)
	if _, err := service.CreateFolder(context.Background(), 7, nil, "   "); err != ErrNameRequired {
		t.Fatalf("create folder error = %v", err)
	}
	if err := service.RenameFolder(context.Background(), 7, 1, "\t"); err != ErrNameRequired {
		t.Fatalf("rename folder error = %v", err)
	}
	if err := service.RenameFile(context.Background(), 7, 1, " "); err != ErrNameRequired {
		t.Fatalf("rename file error = %v", err)
	}
}
