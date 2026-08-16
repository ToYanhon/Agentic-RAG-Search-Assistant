package httpapi

import (
	"context"
	"errors"
	"io"
	"net/http"
	"path/filepath"
	"strconv"

	"github.com/clouddrive-ai/backend/internal/auth"
	"github.com/clouddrive-ai/backend/internal/file"
	"github.com/clouddrive-ai/backend/internal/response"
)

type fileService interface {
	Upload(context.Context, int64, *int64, string, string, []byte) (file.Record, error)
	Download(context.Context, int64, int64) (file.Record, io.ReadCloser, error)
	DownloadRange(context.Context, int64, int64, int64, int64) (file.Record, io.ReadCloser, error)
	Delete(context.Context, int64, int64) error
	DeleteFolder(context.Context, int64, int64) error
	ChecksumInstant(context.Context, int64, string, string, int64, *int64) (file.Record, bool, error)
	CreateTextFile(context.Context, int64, string, string, *int64) (file.Record, error)
	OverwriteContent(context.Context, int64, int64, string) (file.Record, error)
	ReadContent(context.Context, int64, int64, int, *int) (file.ContentView, error)
}

func (h Handler) fileRoutes(mux *http.ServeMux) {
	mux.HandleFunc("POST /api/v1/files/upload", h.protected(h.uploadFile))
	mux.HandleFunc("GET /api/v1/files/{id}/download", h.protected(h.downloadFile))
	mux.HandleFunc("DELETE /api/v1/files/{id}", h.protected(h.deleteFile))
	mux.HandleFunc("DELETE /api/v1/folders/{id}", h.protected(h.deleteFolder))
	mux.HandleFunc("POST /api/v1/files/checksum", h.protected(h.checksum))
	mux.HandleFunc("POST /api/v1/files/text", h.protected(h.createTextFile))
	mux.HandleFunc("PUT /api/v1/files/{id}/content", h.protected(h.overwriteContent))
	mux.HandleFunc("GET /api/v1/files/{id}/content", h.protected(h.readContent))
}

type checksumRequest struct {
	MD5      string `json:"md5"`
	Name     string `json:"name"`
	Size     int64  `json:"size"`
	FolderID *int64 `json:"folder_id"`
}

func (h Handler) checksum(w http.ResponseWriter, r *http.Request) {
	var req checksumRequest
	if !decode(w, r, &req) {
		return
	}
	if req.MD5 == "" || req.Name == "" || req.Size <= 0 {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "validation failed")
		return
	}
	record, instant, err := h.files.ChecksumInstant(r.Context(), userID(r.Context()), req.MD5, req.Name, req.Size, req.FolderID)
	if writeFileError(w, err) {
		return
	}
	if !instant {
		response.OK(w, map[string]any{"instant": false})
		return
	}
	response.OK(w, map[string]any{"instant": true, "file": fileResponse(record)})
}

type textRequest struct {
	Name     string `json:"name"`
	Content  string `json:"content"`
	FolderID *int64 `json:"folder_id"`
}
type contentRequest struct {
	Content string `json:"content"`
}

func (h Handler) createTextFile(w http.ResponseWriter, r *http.Request) {
	if CallerFromContext(r.Context()) != CallerAgent {
		response.Error(w, http.StatusForbidden, response.Forbidden, "agent only")
		return
	}
	var req textRequest
	if !decode(w, r, &req) {
		return
	}
	if req.Name == "" || req.Content == "" {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "validation failed")
		return
	}
	if int64(len([]byte(req.Content))) > h.directMaxBytes {
		response.Error(w, http.StatusRequestEntityTooLarge, response.FileTooLarge, "content too large")
		return
	}
	record, err := h.files.CreateTextFile(r.Context(), userID(r.Context()), req.Name, req.Content, req.FolderID)
	if writeFileError(w, err) {
		return
	}
	response.Created(w, fileResponse(record))
}

func (h Handler) overwriteContent(w http.ResponseWriter, r *http.Request) {
	if CallerFromContext(r.Context()) != CallerAgent {
		response.Error(w, http.StatusForbidden, response.Forbidden, "agent only")
		return
	}
	id, err := catalogID(r)
	if err != nil {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "invalid file id")
		return
	}
	var req contentRequest
	if !decode(w, r, &req) {
		return
	}
	if req.Content == "" {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "validation failed")
		return
	}
	record, err := h.files.OverwriteContent(r.Context(), userID(r.Context()), int64(id), req.Content)
	if writeFileError(w, err) {
		return
	}
	response.OK(w, fileResponse(record))
}

func (h Handler) readContent(w http.ResponseWriter, r *http.Request) {
	id, err := catalogID(r)
	if err != nil {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "invalid file id")
		return
	}
	offset := 1
	if raw := r.URL.Query().Get("offset"); raw != "" {
		parsed, err := strconv.Atoi(raw)
		if err != nil {
			response.Error(w, http.StatusBadRequest, response.BadRequest, "invalid offset")
			return
		}
		offset = parsed
	}
	var limit *int
	if raw := r.URL.Query().Get("limit"); raw != "" {
		parsed, err := strconv.Atoi(raw)
		if err != nil {
			response.Error(w, http.StatusBadRequest, response.BadRequest, "invalid limit")
			return
		}
		limit = &parsed
	}
	view, err := h.files.ReadContent(r.Context(), userID(r.Context()), int64(id), offset, limit)
	if writeFileError(w, err) {
		return
	}
	response.OK(w, view)
}

func (h Handler) uploadFile(w http.ResponseWriter, r *http.Request) {
	if err := r.ParseMultipartForm(h.directMaxBytes + 1024); err != nil {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "invalid multipart form")
		return
	}
	upload, header, err := r.FormFile("file")
	if err != nil {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "file is required")
		return
	}
	defer upload.Close()
	if header.Size > h.directMaxBytes {
		response.Error(w, http.StatusRequestEntityTooLarge, response.FileTooLarge, "file too large, use multipart upload for files over 50MB")
		return
	}
	data, err := io.ReadAll(io.LimitReader(upload, h.directMaxBytes+1))
	if err != nil || int64(len(data)) > h.directMaxBytes {
		response.Error(w, http.StatusRequestEntityTooLarge, response.FileTooLarge, "file too large, use multipart upload for files over 50MB")
		return
	}
	if len(data) == 0 {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "file is required")
		return
	}
	folderID, err := optionalID(r.FormValue("folder_id"))
	if err != nil {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "invalid folder id")
		return
	}
	mime := header.Header.Get("Content-Type")
	if mime == "" {
		mime = "application/octet-stream"
	}
	record, err := h.files.Upload(r.Context(), userID(r.Context()), folderID, filepath.Base(header.Filename), mime, data)
	if writeFileError(w, err) {
		return
	}
	response.Created(w, fileResponse(record))
}

func (h Handler) downloadFile(w http.ResponseWriter, r *http.Request) {
	id, err := catalogID(r)
	if err != nil {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "invalid file id")
		return
	}
	record, body, err := h.files.Download(r.Context(), userID(r.Context()), int64(id))
	if writeFileError(w, err) {
		return
	}
	writeDownload(w, r, record, body, func(offset, length int64) (file.Record, io.ReadCloser, error) {
		return h.files.DownloadRange(r.Context(), userID(r.Context()), int64(id), offset, length)
	})
}

func (h Handler) deleteFile(w http.ResponseWriter, r *http.Request) {
	id, err := catalogID(r)
	if err != nil {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "invalid file id")
		return
	}
	if writeFileError(w, h.files.Delete(r.Context(), userID(r.Context()), int64(id))) {
		return
	}
	response.OK(w, nil)
}

func (h Handler) deleteFolder(w http.ResponseWriter, r *http.Request) {
	id, err := catalogID(r)
	if err != nil {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "invalid folder id")
		return
	}
	if writeFileError(w, h.files.DeleteFolder(r.Context(), userID(r.Context()), int64(id))) {
		return
	}
	response.OK(w, nil)
}

func optionalID(value string) (*int64, error) {
	if value == "" {
		return nil, nil
	}
	id, err := strconv.ParseInt(value, 10, 64)
	if err != nil || id < 0 {
		return nil, errors.New("invalid id")
	}
	return &id, nil
}

func fileResponse(record file.Record) map[string]any {
	created := ""
	if !record.CreatedAt.IsZero() {
		created = record.CreatedAt.UTC().Format("2006-01-02T15:04:05Z")
	}
	return map[string]any{"id": record.ID, "name": record.Name, "size": record.Size, "mime_type": record.MimeType, "md5": record.MD5, "folder_id": record.FolderID, "created_at": created}
}

func writeFileError(w http.ResponseWriter, err error) bool {
	if err == nil {
		return false
	}
	switch {
	case errors.Is(err, auth.ErrNotFound), errors.Is(err, file.ErrNotFound):
		response.Error(w, http.StatusNotFound, response.NotFound, "resource not found")
	case errors.Is(err, file.ErrForbidden):
		response.Error(w, http.StatusForbidden, response.Forbidden, "access denied")
	case errors.Is(err, file.ErrStorageExceeded):
		response.Error(w, http.StatusRequestEntityTooLarge, response.StorageExceeded, "storage limit exceeded")
	case errors.Is(err, file.ErrNotTextFile):
		response.Error(w, http.StatusBadRequest, response.BadRequest, "not a text file")
	default:
		response.Error(w, http.StatusInternalServerError, response.Internal, "internal error")
	}
	return true
}
