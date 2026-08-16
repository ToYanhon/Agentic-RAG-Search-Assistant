package httpapi

import (
	"context"
	"errors"
	"github.com/clouddrive-ai/backend/internal/llmconfig"
	"github.com/clouddrive-ai/backend/internal/response"
	"net/http"
)

type llmConfigService interface {
	Upsert(context.Context, int64, string, string, string, string) error
	List(context.Context, int64) ([]llmconfig.View, error)
	Delete(context.Context, int64, string) error
}

func (h Handler) llmConfigRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /api/v1/llm-config", h.protected(h.listLLMConfig))
	mux.HandleFunc("PUT /api/v1/llm-config", h.protected(h.saveLLMConfig))
	mux.HandleFunc("DELETE /api/v1/llm-config/{provider}", h.protected(h.deleteLLMConfig))
}
func (h Handler) listLLMConfig(w http.ResponseWriter, r *http.Request) {
	values, err := h.llmConfigs.List(r.Context(), userID(r.Context()))
	if writeLLMConfigError(w, err) {
		return
	}
	response.OK(w, map[string]any{"configs": values})
}

type llmConfigRequest struct {
	Provider string `json:"provider"`
	BaseURL  string `json:"base_url"`
	APIKey   string `json:"api_key"`
	Model    string `json:"model"`
}

func (h Handler) saveLLMConfig(w http.ResponseWriter, r *http.Request) {
	var req llmConfigRequest
	if !decode(w, r, &req) {
		return
	}
	if writeLLMConfigError(w, h.llmConfigs.Upsert(r.Context(), userID(r.Context()), req.Provider, req.BaseURL, req.APIKey, req.Model)) {
		return
	}
	response.OK(w, nil)
}
func (h Handler) deleteLLMConfig(w http.ResponseWriter, r *http.Request) {
	if writeLLMConfigError(w, h.llmConfigs.Delete(r.Context(), userID(r.Context()), r.PathValue("provider"))) {
		return
	}
	response.OK(w, nil)
}
func writeLLMConfigError(w http.ResponseWriter, err error) bool {
	if err == nil {
		return false
	}
	if errors.Is(err, llmconfig.ErrProviderRequired) {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "provider required")
	} else if errors.Is(err, llmconfig.ErrProviderTooLong) {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "validation failed")
	} else {
		response.Error(w, http.StatusInternalServerError, response.Internal, "internal error")
	}
	return true
}
