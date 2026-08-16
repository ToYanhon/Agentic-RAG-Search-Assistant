package httpapi

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strconv"

	"github.com/clouddrive-ai/backend/internal/auth"
	"github.com/clouddrive-ai/backend/internal/file"
	"github.com/clouddrive-ai/backend/internal/response"
	"github.com/clouddrive-ai/backend/internal/share"
)

type shareService interface {
	Create(context.Context, int64, int64, *int) (share.Record, error)
	Revoke(context.Context, int64, int64) error
	Access(context.Context, string) (file.Record, error)
	Download(context.Context, string) (file.Record, io.ReadCloser, error)
	DownloadRange(context.Context, string, int64, int64) (file.Record, io.ReadCloser, error)
}

func (h Handler) shareRoutes(mux *http.ServeMux) {
	mux.HandleFunc("POST /api/v1/shares", h.protected(h.createShare))
	mux.HandleFunc("DELETE /api/v1/shares/{id}", h.protected(h.deleteShare))
	mux.HandleFunc("GET /s/{token}", h.publicShare)
	mux.HandleFunc("GET /s/{token}/download", h.publicShareDownload)
}

type createShareRequest struct {
	FileID      int64 `json:"file_id"`
	ExpireHours *int  `json:"expire_hours"`
}

func (h Handler) createShare(w http.ResponseWriter, r *http.Request) {
	var req createShareRequest
	if !decode(w, r, &req) {
		return
	}
	if req.FileID <= 0 {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "validation failed")
		return
	}
	created, err := h.shares.Create(r.Context(), userID(r.Context()), req.FileID, req.ExpireHours)
	if writeShareError(w, err) {
		return
	}
	response.Created(w, map[string]any{"id": created.ID, "token": created.Token, "url": "/s/" + created.Token})
}

func (h Handler) deleteShare(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil || id <= 0 {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "invalid share id")
		return
	}
	if writeShareError(w, h.shares.Revoke(r.Context(), userID(r.Context()), id)) {
		return
	}
	response.OK(w, nil)
}

func (h Handler) publicShare(w http.ResponseWriter, r *http.Request) {
	record, err := h.shares.Access(r.Context(), r.PathValue("token"))
	if writeShareError(w, err) {
		return
	}
	response.OK(w, fileResponse(record))
}

func (h Handler) publicShareDownload(w http.ResponseWriter, r *http.Request) {
	record, body, err := h.shares.Download(r.Context(), r.PathValue("token"))
	if writeShareError(w, err) {
		return
	}
	writeDownload(w, r, record, body, func(offset, length int64) (file.Record, io.ReadCloser, error) {
		return h.shares.DownloadRange(r.Context(), r.PathValue("token"), offset, length)
	})
}

func writeShareError(w http.ResponseWriter, err error) bool {
	if err == nil {
		return false
	}
	switch {
	case errors.Is(err, share.ErrNotFound), errors.Is(err, auth.ErrNotFound), errors.Is(err, file.ErrNotFound):
		response.Error(w, http.StatusNotFound, response.NotFound, "share not found")
	case errors.Is(err, file.ErrForbidden):
		response.Error(w, http.StatusForbidden, response.Forbidden, "access denied")
	default:
		response.Error(w, http.StatusInternalServerError, response.Internal, "internal error")
	}
	return true
}
