// Package httpapi exposes only the non-production M1 authentication slice.
package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/mail"
	"strconv"
	"strings"

	"github.com/clouddrive-ai/backend/internal/auth"
	"github.com/clouddrive-ai/backend/internal/catalog"
	"github.com/clouddrive-ai/backend/internal/response"
)

type Handler struct {
	service        *auth.Service
	agents         *auth.AgentTokenManager
	catalog        catalogService
	files          fileService
	multipart      multipartService
	shares         shareService
	llmConfigs     llmConfigService
	agentProxy     agentProxyService
	chunkMaxBytes  int64
	fileMaxBytes   int64
	directMaxBytes int64
}

type catalogService interface {
	ListFiles(context.Context, int, int, int) (catalog.Page, error)
	SearchFiles(context.Context, int, string, int, int) (catalog.Page, error)
	GetFile(context.Context, int, int) (catalog.File, error)
	Root(context.Context, int) ([]catalog.FolderView, error)
	Tree(context.Context, int, int) (catalog.FolderView, error)
	CreateFolder(context.Context, int, *int64, string) (catalog.Folder, error)
	RenameFolder(context.Context, int, int, string) error
	MoveFolder(context.Context, int, int, *int64) error
	RenameFile(context.Context, int, int, string) error
	MoveFile(context.Context, int, int, *int64) error
}

func New(service *auth.Service, agents *auth.AgentTokenManager) http.Handler {
	h := Handler{service: service, agents: agents}
	mux := http.NewServeMux()
	h.registerRoutes(mux)
	return mux
}

func NewWithCatalog(service *auth.Service, agents *auth.AgentTokenManager, catalog catalogService) http.Handler {
	h := Handler{service: service, agents: agents, catalog: catalog}
	mux := http.NewServeMux()
	h.registerRoutes(mux)
	return mux
}

func NewWithServices(service *auth.Service, agents *auth.AgentTokenManager, catalog catalogService, files fileService, directMaxBytes int64) http.Handler {
	h := Handler{service: service, agents: agents, catalog: catalog, files: files, directMaxBytes: directMaxBytes}
	mux := http.NewServeMux()
	h.registerRoutes(mux)
	return mux
}

func NewWithAllServices(service *auth.Service, agents *auth.AgentTokenManager, catalog catalogService, files fileService, multipart multipartService, directMaxBytes, chunkMaxBytes, fileMaxBytes int64) http.Handler {
	h := Handler{service: service, agents: agents, catalog: catalog, files: files, multipart: multipart, directMaxBytes: directMaxBytes, chunkMaxBytes: chunkMaxBytes, fileMaxBytes: fileMaxBytes}
	mux := http.NewServeMux()
	h.registerRoutes(mux)
	return mux
}

func NewWithCompleteServices(service *auth.Service, agents *auth.AgentTokenManager, catalog catalogService, files fileService, multipart multipartService, shares shareService, directMaxBytes, chunkMaxBytes, fileMaxBytes int64) http.Handler {
	h := Handler{service: service, agents: agents, catalog: catalog, files: files, multipart: multipart, shares: shares, directMaxBytes: directMaxBytes, chunkMaxBytes: chunkMaxBytes, fileMaxBytes: fileMaxBytes}
	mux := http.NewServeMux()
	h.registerRoutes(mux)
	return mux
}

func NewWithAllDomainServices(service *auth.Service, agents *auth.AgentTokenManager, catalog catalogService, files fileService, multipart multipartService, shares shareService, llmConfigs llmConfigService, directMaxBytes, chunkMaxBytes, fileMaxBytes int64) http.Handler {
	h := Handler{service: service, agents: agents, catalog: catalog, files: files, multipart: multipart, shares: shares, llmConfigs: llmConfigs, directMaxBytes: directMaxBytes, chunkMaxBytes: chunkMaxBytes, fileMaxBytes: fileMaxBytes}
	mux := http.NewServeMux()
	h.registerRoutes(mux)
	return mux
}

func NewWithProxy(service *auth.Service, agents *auth.AgentTokenManager, catalog catalogService, files fileService, multipart multipartService, shares shareService, llmConfigs llmConfigService, agentProxy agentProxyService, directMaxBytes, chunkMaxBytes, fileMaxBytes int64) http.Handler {
	h := Handler{service: service, agents: agents, catalog: catalog, files: files, multipart: multipart, shares: shares, llmConfigs: llmConfigs, agentProxy: agentProxy, directMaxBytes: directMaxBytes, chunkMaxBytes: chunkMaxBytes, fileMaxBytes: fileMaxBytes}
	mux := http.NewServeMux()
	h.registerRoutes(mux)
	return mux
}

func (h Handler) registerRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /health", h.health)
	mux.HandleFunc("POST /api/v1/auth/register", h.register)
	mux.HandleFunc("POST /api/v1/auth/login", h.login)
	mux.HandleFunc("POST /api/v1/auth/logout", h.logout)
	mux.HandleFunc("GET /api/v1/auth/profile", h.protected(h.profile))
	mux.HandleFunc("PUT /api/v1/auth/profile", h.protected(h.updateProfile))
	mux.HandleFunc("GET /api/v1/auth/storage/usage", h.protected(h.storageUsage))
	mux.HandleFunc("PUT /api/v1/auth/password", h.protected(h.changePassword))
	if h.catalog != nil {
		h.catalogRoutes(mux)
	}
	if h.files != nil {
		h.fileRoutes(mux)
	}
	if h.multipart != nil {
		h.multipartRoutes(mux)
	}
	if h.shares != nil {
		h.shareRoutes(mux)
	}
	if h.llmConfigs != nil {
		h.llmConfigRoutes(mux)
	}
	if h.agentProxy != nil {
		h.agentRoutes(mux)
	}
}

func (h Handler) health(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
}

type registerRequest struct {
	Username string `json:"username"`
	Email    string `json:"email"`
	Password string `json:"password"`
}

func (h Handler) register(w http.ResponseWriter, r *http.Request) {
	var request registerRequest
	if !decode(w, r, &request) {
		return
	}
	if len(strings.TrimSpace(request.Username)) < 3 || len(request.Username) > 64 || !validEmail(request.Email) || len(strings.TrimSpace(request.Password)) < 6 {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "validation failed")
		return
	}
	err := h.service.Register(r.Context(), request.Username, request.Email, request.Password)
	if writeAuthError(w, err) {
		return
	}
	response.Created(w, map[string]string{"message": "register success"})
}

// validEmail 执行基础邮箱输入约束，不将显示名等宽松地址形式写入用户表。
func validEmail(value string) bool {
	address, err := mail.ParseAddress(value)
	return err == nil && address.Address == value && strings.Contains(address.Address, "@")
}

type loginRequest struct {
	Username string `json:"username"`
	Password string `json:"password"`
}

func (h Handler) login(w http.ResponseWriter, r *http.Request) {
	var request loginRequest
	if !decode(w, r, &request) {
		return
	}
	if strings.TrimSpace(request.Username) == "" || strings.TrimSpace(request.Password) == "" {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "validation failed")
		return
	}
	token, profile, err := h.service.Login(r.Context(), request.Username, request.Password)
	if errors.Is(err, auth.ErrInvalidCredentials) {
		response.Error(w, http.StatusUnauthorized, response.InvalidCredentials, err.Error())
		return
	}
	if writeAuthError(w, err) {
		return
	}
	response.OK(w, map[string]any{"token": token, "user": profile})
}
func (h Handler) logout(w http.ResponseWriter, r *http.Request) {
	raw := bearer(r)
	if raw == "" || h.service.Logout(r.Context(), raw) != nil {
		response.Error(w, http.StatusUnauthorized, response.Unauthorized, "unauthorized")
		return
	}
	response.OK(w, map[string]string{"message": "logged out"})
}
func (h Handler) profile(w http.ResponseWriter, r *http.Request) {
	profile, err := h.service.Profile(r.Context(), userID(r.Context()))
	if writeAuthError(w, err) {
		return
	}
	response.OK(w, profile)
}
func (h Handler) storageUsage(w http.ResponseWriter, r *http.Request) {
	profile, err := h.service.Profile(r.Context(), userID(r.Context()))
	if writeAuthError(w, err) {
		return
	}
	response.OK(w, map[string]int64{"storage_used": profile.StorageUsed, "storage_limit": profile.StorageLimit})
}

type updateProfileRequest struct {
	Username string `json:"username"`
}

func (h Handler) updateProfile(w http.ResponseWriter, r *http.Request) {
	var request updateProfileRequest
	if !decode(w, r, &request) {
		return
	}
	if len(strings.TrimSpace(request.Username)) < 3 || len(request.Username) > 64 {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "nothing to update")
		return
	}
	profile, err := h.service.UpdateUsername(r.Context(), userID(r.Context()), request.Username)
	if writeAuthError(w, err) {
		return
	}
	response.OK(w, profile)
}

type passwordRequest struct {
	OldPassword string `json:"old_password"`
	NewPassword string `json:"new_password"`
}

func (h Handler) changePassword(w http.ResponseWriter, r *http.Request) {
	var request passwordRequest
	if !decode(w, r, &request) {
		return
	}
	if strings.TrimSpace(request.OldPassword) == "" || len(strings.TrimSpace(request.NewPassword)) < 6 {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "validation failed")
		return
	}
	err := h.service.ChangePassword(r.Context(), userID(r.Context()), request.OldPassword, request.NewPassword)
	if writeAuthError(w, err) {
		return
	}
	response.OK(w, map[string]string{"message": "password changed"})
}

type contextKey string

const (
	userIDKey contextKey = "user_id"
	callerKey contextKey = "caller"
)

type Caller string

const (
	CallerUser  Caller = "user"
	CallerAgent Caller = "agent"
)

func (h Handler) protected(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if token := r.Header.Get("X-Agent-Token"); token != "" {
			userID, err := strconv.ParseInt(first(r.URL.Query().Get("user_id"), r.Header.Get("X-User-Id")), 10, 64)
			if !h.agents.Validate(r.Context(), token) {
				response.Error(w, http.StatusUnauthorized, response.Unauthorized, "invalid agent token")
				return
			}
			if err != nil || userID <= 0 {
				response.Error(w, http.StatusUnauthorized, response.Unauthorized, "missing user_id for internal call")
				return
			}
			ctx := context.WithValue(r.Context(), userIDKey, userID)
			next(w, r.WithContext(context.WithValue(ctx, callerKey, CallerAgent)))
			return
		}
		raw := bearer(r)
		// 浏览器 video 元素无法附加 Authorization；仅下载端点允许 JWT query 回退。
		if raw == "" && strings.HasSuffix(r.URL.Path, "/download") {
			raw = r.URL.Query().Get("token")
		}
		if raw == "" {
			response.Error(w, http.StatusUnauthorized, response.Unauthorized, "missing or malformed token")
			return
		}
		claims, err := h.service.Authenticate(r.Context(), raw)
		if err != nil {
			response.Error(w, http.StatusUnauthorized, response.TokenExpired, "invalid or expired token")
			return
		}
		ctx := context.WithValue(r.Context(), userIDKey, claims.UserID)
		next(w, r.WithContext(context.WithValue(ctx, callerKey, CallerUser)))
	}
}
func userID(ctx context.Context) int64 { value, _ := ctx.Value(userIDKey).(int64); return value }
func CallerFromContext(ctx context.Context) Caller {
	value, _ := ctx.Value(callerKey).(Caller)
	return value
}
func bearer(r *http.Request) string {
	const prefix = "Bearer "
	value := r.Header.Get("Authorization")
	if !strings.HasPrefix(value, prefix) {
		return ""
	}
	return strings.TrimPrefix(value, prefix)
}
func first(values ...string) string {
	for _, value := range values {
		if value != "" {
			return value
		}
	}
	return ""
}
func decode(w http.ResponseWriter, r *http.Request, target any) bool {
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "invalid request body")
		return false
	}
	return true
}
func writeAuthError(w http.ResponseWriter, err error) bool {
	if err == nil {
		return false
	}
	switch {
	case errors.Is(err, auth.ErrUsernameTaken):
		response.Error(w, http.StatusConflict, response.UsernameTaken, err.Error())
	case errors.Is(err, auth.ErrEmailTaken):
		response.Error(w, http.StatusConflict, response.EmailTaken, err.Error())
	case errors.Is(err, auth.ErrDuplicateUser):
		response.Error(w, http.StatusConflict, response.Conflict, "user already exists")
	case errors.Is(err, auth.ErrNotFound):
		response.Error(w, http.StatusNotFound, response.NotFound, err.Error())
	case errors.Is(err, auth.ErrWrongPassword):
		response.Error(w, http.StatusBadRequest, response.BadRequest, err.Error())
	default:
		response.Error(w, http.StatusInternalServerError, response.Internal, "internal server error")
	}
	return true
}
