package httpapi

import (
	"errors"
	"net/http"
	"strconv"

	"github.com/clouddrive-ai/backend/internal/auth"
	"github.com/clouddrive-ai/backend/internal/catalog"
	"github.com/clouddrive-ai/backend/internal/response"
)

func (h Handler) catalogRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /api/v1/files", h.protected(h.listFiles))
	mux.HandleFunc("GET /api/v1/files/search", h.protected(h.searchFiles))
	mux.HandleFunc("GET /api/v1/files/{id}", h.protected(h.getFile))
	mux.HandleFunc("PUT /api/v1/files/{id}", h.protected(h.renameFile))
	mux.HandleFunc("PUT /api/v1/files/{id}/move", h.protected(h.moveFile))
	mux.HandleFunc("GET /api/v1/folders/root", h.protected(h.rootFolders))
	mux.HandleFunc("GET /api/v1/folders/{id}", h.protected(h.folderTree))
	mux.HandleFunc("POST /api/v1/folders", h.protected(h.createFolder))
	mux.HandleFunc("PUT /api/v1/folders/{id}", h.protected(h.renameFolder))
	mux.HandleFunc("PUT /api/v1/folders/{id}/move", h.protected(h.moveFolder))
}

type folderRequest struct {
	Name     string `json:"name"`
	ParentID *int64 `json:"parent_id"`
}

type moveFolderRequest struct {
	TargetParentID *int64 `json:"target_parent_id"`
}

type renameFileRequest struct {
	Name string `json:"name"`
}

type moveFileRequest struct {
	TargetFolderID *int64 `json:"target_folder_id"`
}

func (h Handler) renameFile(w http.ResponseWriter, r *http.Request) {
	id, err := catalogID(r)
	if err != nil {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "invalid file id")
		return
	}
	var req renameFileRequest
	if !decode(w, r, &req) {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "name required")
		return
	}
	if err := h.catalog.RenameFile(r.Context(), int(userID(r.Context())), id, req.Name); writeCatalogError(w, err) {
		return
	}
	response.OK(w, nil)
}

func (h Handler) moveFile(w http.ResponseWriter, r *http.Request) {
	id, err := catalogID(r)
	if err != nil {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "invalid file id")
		return
	}
	var req moveFileRequest
	if !decode(w, r, &req) {
		return
	}
	if err := h.catalog.MoveFile(r.Context(), int(userID(r.Context())), id, req.TargetFolderID); writeCatalogError(w, err) {
		return
	}
	response.OK(w, nil)
}

func (h Handler) createFolder(w http.ResponseWriter, r *http.Request) {
	var req folderRequest
	if !decode(w, r, &req) {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "name required")
		return
	}
	folder, err := h.catalog.CreateFolder(r.Context(), int(userID(r.Context())), req.ParentID, req.Name)
	if writeCatalogError(w, err) {
		return
	}
	response.Created(w, folder)
}

func (h Handler) renameFolder(w http.ResponseWriter, r *http.Request) {
	id, err := catalogID(r)
	if err != nil {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "invalid folder id")
		return
	}
	var req folderRequest
	if !decode(w, r, &req) || req.Name == "" {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "name required")
		return
	}
	if err := h.catalog.RenameFolder(r.Context(), int(userID(r.Context())), id, req.Name); writeCatalogError(w, err) {
		return
	}
	response.OK(w, nil)
}

func (h Handler) moveFolder(w http.ResponseWriter, r *http.Request) {
	id, err := catalogID(r)
	if err != nil {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "invalid folder id")
		return
	}
	var req moveFolderRequest
	if !decode(w, r, &req) {
		return
	}
	if err := h.catalog.MoveFolder(r.Context(), int(userID(r.Context())), id, req.TargetParentID); writeCatalogError(w, err) {
		return
	}
	response.OK(w, nil)
}

func catalogID(r *http.Request) (int, error) {
	id, err := strconv.Atoi(r.PathValue("id"))
	if err != nil || id <= 0 {
		return 0, errors.New("invalid id")
	}
	return id, nil
}

func (h Handler) listFiles(w http.ResponseWriter, r *http.Request) {
	page, pageSize := pagination(r)
	result, err := h.catalog.ListFiles(r.Context(), int(userID(r.Context())), page, pageSize)
	if writeCatalogError(w, err) {
		return
	}
	response.OK(w, result)
}

func (h Handler) searchFiles(w http.ResponseWriter, r *http.Request) {
	query := r.URL.Query().Get("q")
	if query == "" {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "query required")
		return
	}
	page, pageSize := pagination(r)
	result, err := h.catalog.SearchFiles(r.Context(), int(userID(r.Context())), query, page, pageSize)
	if writeCatalogError(w, err) {
		return
	}
	response.OK(w, result)
}

func (h Handler) getFile(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.Atoi(r.PathValue("id"))
	if err != nil || id <= 0 {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "invalid file id")
		return
	}
	file, err := h.catalog.GetFile(r.Context(), int(userID(r.Context())), id)
	if writeCatalogError(w, err) {
		return
	}
	response.OK(w, file)
}

func (h Handler) rootFolders(w http.ResponseWriter, r *http.Request) {
	result, err := h.catalog.Root(r.Context(), int(userID(r.Context())))
	if writeCatalogError(w, err) {
		return
	}
	response.OK(w, result)
}

func (h Handler) folderTree(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.Atoi(r.PathValue("id"))
	if err != nil || id <= 0 {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "invalid folder id")
		return
	}
	result, err := h.catalog.Tree(r.Context(), int(userID(r.Context())), id)
	if writeCatalogError(w, err) {
		return
	}
	response.OK(w, result)
}

func pagination(r *http.Request) (int, int) {
	page, _ := strconv.Atoi(r.URL.Query().Get("page"))
	pageSize, _ := strconv.Atoi(r.URL.Query().Get("page_size"))
	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}
	return page, pageSize
}

func writeCatalogError(w http.ResponseWriter, err error) bool {
	if err == nil {
		return false
	}
	switch {
	case errors.Is(err, auth.ErrNotFound), errors.Is(err, catalog.ErrNotFound):
		response.Error(w, http.StatusNotFound, response.NotFound, "resource not found")
	case errors.Is(err, catalog.ErrNameRequired):
		response.Error(w, http.StatusBadRequest, response.BadRequest, "name required")
	case errors.Is(err, catalog.ErrForbidden):
		response.Error(w, http.StatusForbidden, response.Forbidden, "access denied")
	case errors.Is(err, catalog.ErrFolderCycle):
		response.Error(w, http.StatusUnprocessableEntity, response.FolderCycle, err.Error())
	default:
		response.Error(w, http.StatusInternalServerError, response.Internal, "internal server error")
	}
	return true
}
