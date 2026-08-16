package httpapi

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strings"

	"github.com/clouddrive-ai/backend/internal/agentproxy"
	"github.com/clouddrive-ai/backend/internal/response"
)

type agentProxyService interface {
	Forward(context.Context, string, string, string, []byte, http.Header, int64) (agentproxy.Response, error)
}

func (h Handler) agentRoutes(mux *http.ServeMux) {
	routes := []struct{ method, path, target string }{
		{"POST", "/api/v1/agent/chat/sessions", "/chat/sessions"}, {"GET", "/api/v1/agent/chat/sessions", "/chat/sessions"},
		{"GET", "/api/v1/agent/chat/sessions/{session_id}/messages", "/chat/sessions/{session_id}/messages"}, {"POST", "/api/v1/agent/chat/sessions/{session_id}/messages", "/chat/sessions/{session_id}/messages"}, {"POST", "/api/v1/agent/chat/sessions/{session_id}/messages/append", "/chat/sessions/{session_id}/messages/append"}, {"PUT", "/api/v1/agent/chat/sessions/{session_id}", "/chat/sessions/{session_id}"}, {"DELETE", "/api/v1/agent/chat/sessions/{session_id}", "/chat/sessions/{session_id}"},
		{"POST", "/api/v1/agent/summary/{file_id}", "/summary/{file_id}"}, {"POST", "/api/v1/agent/index/status", "/index/status"}, {"POST", "/api/v1/agent/index/{file_id}", "/index/{file_id}"}, {"DELETE", "/api/v1/agent/index/{file_id}", "/index/{file_id}"}, {"POST", "/api/v1/agent/index/folder/{folder_id}/status", "/index/folder/{folder_id}/status"}, {"POST", "/api/v1/agent/index/folder/{folder_id}", "/index/folder/{folder_id}"}, {"DELETE", "/api/v1/agent/index/folder/{folder_id}", "/index/folder/{folder_id}"}, {"GET", "/api/v1/agent/memory", "/memory"}, {"DELETE", "/api/v1/agent/memory", "/memory"},
	}
	for _, route := range routes {
		target := route.target
		mux.HandleFunc(route.method+" "+route.path, h.protected(func(w http.ResponseWriter, r *http.Request) { h.proxyAgent(w, r, target) }))
	}
}

func (h Handler) proxyAgent(w http.ResponseWriter, r *http.Request, target string) {
	payload, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 32<<20))
	if err != nil {
		response.Error(w, http.StatusBadRequest, response.BadRequest, "invalid request body")
		return
	}
	for key, value := range map[string]string{"{session_id}": r.PathValue("session_id"), "{file_id}": r.PathValue("file_id"), "{folder_id}": r.PathValue("folder_id")} {
		target = strings.ReplaceAll(target, key, value)
	}
	upstream, err := h.agentProxy.Forward(r.Context(), r.Method, target, r.URL.RawQuery, payload, r.Header, userID(r.Context()))
	if err != nil {
		if errors.Is(err, agentproxy.ErrBusy) {
			response.Error(w, http.StatusServiceUnavailable, response.Internal, "agent busy")
		} else {
			response.Error(w, http.StatusBadGateway, response.Internal, "agent unavailable")
		}
		return
	}
	defer upstream.Body.Close()
	for name, values := range upstream.Header {
		if blockedAgentResponseHeader(name) || len(values) == 0 {
			continue
		}
		w.Header().Set(name, values[0])
	}
	w.WriteHeader(upstream.Status)
	buffer := make([]byte, 32*1024)
	for {
		count, readErr := upstream.Body.Read(buffer)
		if count > 0 {
			_, _ = w.Write(buffer[:count])
			if flush, ok := w.(http.Flusher); ok {
				flush.Flush()
			}
		}
		if readErr != nil {
			break
		}
	}
}
func blockedAgentResponseHeader(name string) bool {
	switch strings.ToLower(name) {
	case "connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length", "expect", "x-user-id", "x-agent-token", "x-llm-provider", "x-llm-base-url", "x-llm-key", "x-llm-model", "x-tavily-key":
		return true
	}
	return false
}
