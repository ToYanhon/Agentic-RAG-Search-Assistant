package agentproxy

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/clouddrive-ai/backend/internal/auth"
	"github.com/clouddrive-ai/backend/internal/llmconfig"
)

func TestForwardStripsForgedInternalHeaders(t *testing.T) {
	var received http.Header
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		received = r.Header.Clone()
		w.WriteHeader(http.StatusNoContent)
	}))
	defer upstream.Close()
	client := New(Config{BaseURL: upstream.URL, MaxConcurrent: 1}, agentManager(), llmconfig.NewService(noConfigs{}, noSecret{}))
	headers := http.Header{}
	headers.Set("X-User-Id", "999")
	headers.Set("X-Agent-Token", "forged")
	headers.Set("X-LLM-Key", "forged")
	headers.Set("X-LLM-Provider", "custom")
	headers.Set("X-Request-Id", "request-1")
	response, err := client.Forward(context.Background(), http.MethodGet, "/chat/sessions", "", nil, headers, 7)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = io.ReadAll(response.Body)
	_ = response.Body.Close()
	if received.Get("X-User-Id") != "7" || received.Get("X-Agent-Token") == "forged" || received.Get("X-LLM-Key") != "" || received.Get("X-LLM-Provider") != "custom" || received.Get("X-Request-Id") != "request-1" {
		t.Fatalf("unsafe forwarded headers: %#v", received)
	}
}

func TestForwardOmitsEmptyResolvedKeys(t *testing.T) {
	var received http.Header
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		received = r.Header.Clone()
		w.WriteHeader(http.StatusNoContent)
	}))
	defer upstream.Close()
	client := New(Config{BaseURL: upstream.URL}, agentManager(), llmconfig.NewService(emptyConfigs{}, noSecret{}))
	response, err := client.Forward(context.Background(), http.MethodGet, "/memory", "", nil, http.Header{}, 7)
	if err != nil {
		t.Fatal(err)
	}
	_ = response.Body.Close()
	if values := received.Values("X-Llm-Key"); len(values) != 0 {
		t.Fatalf("empty LLM key should be omitted: %#v", values)
	}
	if values := received.Values("X-Tavily-Key"); len(values) != 0 {
		t.Fatalf("empty Tavily key should be omitted: %#v", values)
	}
}

func TestForwardReturnsBusyAtConcurrencyLimit(t *testing.T) {
	block := make(chan struct{})
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { <-block; w.WriteHeader(http.StatusOK) }))
	defer upstream.Close()
	client := New(Config{BaseURL: upstream.URL, MaxConcurrent: 1}, agentManager(), llmconfig.NewService(noConfigs{}, noSecret{}))
	go func() {
		response, _ := client.Forward(context.Background(), http.MethodGet, "/memory", "", nil, http.Header{}, 1)
		if response.Body != nil {
			_ = response.Body.Close()
		}
	}()
	time.Sleep(20 * time.Millisecond)
	if _, err := client.Forward(context.Background(), http.MethodGet, "/memory", "", nil, http.Header{}, 1); err != ErrBusy {
		t.Fatalf("expected busy, got %v", err)
	}
	close(block)
}

func agentManager() *auth.AgentTokenManager {
	return auth.NewAgentTokenManager(tokenStore{}, nilRandom{})
}

type tokenStore struct{}

func (tokenStore) Save(context.Context, string, time.Duration) error { return nil }
func (tokenStore) Get(context.Context) (string, error)               { return "trusted", nil }

type nilRandom struct{}

func (nilRandom) Generate(int) (string, error) { return "", nil }

type noConfigs struct{}

func (noConfigs) Find(context.Context, int64, string) (llmconfig.Stored, error) {
	return llmconfig.Stored{}, llmconfig.ErrNotFound
}
func (noConfigs) FindAll(context.Context, int64) ([]llmconfig.Stored, error)          { return nil, nil }
func (noConfigs) Upsert(context.Context, int64, string, string, string, string) error { return nil }
func (noConfigs) Delete(context.Context, int64, string) error                         { return nil }

type noSecret struct{}

func (noSecret) Encrypt(string) (string, error) { return "", nil }
func (noSecret) Decrypt(string) (string, error) { return "", nil }

type emptyConfigs struct{ noConfigs }

func (emptyConfigs) Find(_ context.Context, _ int64, provider string) (llmconfig.Stored, error) {
	return llmconfig.Stored{Provider: provider, APIKeyEnc: "stored"}, nil
}
