package httpapi

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strconv"

	"github.com/clouddrive-ai/backend/internal/file"
	"github.com/clouddrive-ai/backend/internal/multipart"
	"github.com/clouddrive-ai/backend/internal/response"
)

type multipartService interface {
	Init(context.Context, int64, string, string, int64, *int64, string, int64) (multipart.Meta, error)
	UploadPart(context.Context, string, int64, int, io.Reader, int64) ([]int, error)
	Complete(context.Context, string, int64) (file.Record, error)
	Abort(context.Context, string, int64) error
}

func (h Handler) multipartRoutes(mux *http.ServeMux) {
	mux.HandleFunc("POST /api/v1/files/multipart/init", h.protected(h.multipartInit))
	mux.HandleFunc("POST /api/v1/files/multipart/{upload_id}/parts", h.protected(h.multipartPart))
	mux.HandleFunc("POST /api/v1/files/multipart/{upload_id}/complete", h.protected(h.multipartComplete))
	mux.HandleFunc("DELETE /api/v1/files/multipart/{upload_id}", h.protected(h.multipartAbort))
}

type multipartInitRequest struct {
	Name      string `json:"name"`
	Size      int64  `json:"size"`
	MimeType  string `json:"mime_type"`
	FolderID  *int64 `json:"folder_id"`
	MD5       string `json:"md5"`
	ChunkSize int64  `json:"chunk_size"`
}

func (h Handler) multipartInit(w http.ResponseWriter, r *http.Request) {
	var req multipartInitRequest
	if !decode(w, r, &req) {
		return
	}
	if req.Name == "" {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "name required")
		return
	}
	if req.Size <= 0 || req.Size > h.fileMaxBytes {
		response.Error(w, http.StatusRequestEntityTooLarge, response.FileTooLarge, "file size exceeds upload limit")
		return
	}
	if req.ChunkSize == 0 {
		req.ChunkSize = 5 * 1024 * 1024
	}
	if req.ChunkSize < 1024*1024 || req.ChunkSize > h.chunkMaxBytes {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "chunk_size out of range (1MB ~ 10MB)")
		return
	}
	meta, err := h.multipart.Init(r.Context(), userID(r.Context()), req.Name, req.MimeType, req.Size, req.FolderID, req.MD5, req.ChunkSize)
	if writeMultipartError(w, err) {
		return
	}
	response.Created(w, map[string]any{"upload_id": meta.UploadID, "chunk_size": meta.ChunkSize, "total_chunks": meta.TotalChunks, "remaining": meta.Remaining})
}

func (h Handler) multipartPart(w http.ResponseWriter, r *http.Request) {
	indexValue := r.URL.Query().Get("index")
	if indexValue == "" {
		indexValue = "0"
	}
	index, err := strconv.Atoi(indexValue)
	if err != nil || index < 0 {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "invalid index")
		return
	}
	part, header, err := r.FormFile("data")
	if err != nil {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "data part required")
		return
	}
	defer part.Close()
	if header.Size <= 0 {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "data part required")
		return
	}
	if header.Size > h.chunkMaxBytes {
		response.Error(w, http.StatusRequestEntityTooLarge, response.FileTooLarge, "part exceeds chunk size limit")
		return
	}
	uploadID := r.PathValue("upload_id")
	received, err := h.multipart.UploadPart(r.Context(), uploadID, userID(r.Context()), index, part, header.Size)
	if writeMultipartError(w, err) {
		return
	}
	response.OK(w, map[string]any{"received": received})
}

func (h Handler) multipartComplete(w http.ResponseWriter, r *http.Request) {
	record, err := h.multipart.Complete(r.Context(), r.PathValue("upload_id"), userID(r.Context()))
	if writeMultipartError(w, err) {
		return
	}
	response.OK(w, fileResponse(record))
}

func (h Handler) multipartAbort(w http.ResponseWriter, r *http.Request) {
	if writeMultipartError(w, h.multipart.Abort(r.Context(), r.PathValue("upload_id"), userID(r.Context()))) {
		return
	}
	response.OK(w, nil)
}

func writeMultipartError(w http.ResponseWriter, err error) bool {
	if err == nil {
		return false
	}
	switch {
	case errors.Is(err, file.ErrForbidden):
		response.Error(w, http.StatusForbidden, response.Forbidden, "access denied")
	case errors.Is(err, file.ErrStorageExceeded):
		response.Error(w, http.StatusRequestEntityTooLarge, response.StorageExceeded, "storage limit exceeded")
	case errors.Is(err, multipart.ErrNotFound):
		response.Error(w, http.StatusNotFound, response.NotFound, "resource not found")
	case errors.Is(err, multipart.ErrIncomplete), errors.Is(err, multipart.ErrSizeMismatch):
		response.Error(w, http.StatusBadRequest, response.BadRequest, err.Error())
	case errors.Is(err, multipart.ErrPartTooSmall):
		response.Error(w, http.StatusBadRequest, response.BadRequest, err.Error())
	default:
		response.Error(w, http.StatusInternalServerError, response.Internal, "internal error")
	}
	return true
}
