package agent

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/clouddrive-ai/backend/internal/auth"
)

func TestIndexSenderUsesExpectedMethodAndTrustedHeaders(t *testing.T) {
	var method string
	var headers http.Header
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		method, headers = r.Method, r.Header.Clone()
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()
	sender := NewIndexSender(server.URL, senderTokenManager("trusted"))
	if !sender.Send(context.Background(), "unindex", 7, 3) {
		t.Fatal("send failed")
	}
	if method != http.MethodDelete || headers.Get("X-User-Id") != "3" || headers.Get("X-Agent-Token") != "trusted" {
		t.Fatalf("method=%q headers=%#v", method, headers)
	}
}

func TestIndexSenderTreatsEmptyBaseURLAsDisabled(t *testing.T) {
	if !NewIndexSender("", senderTokenManager("trusted")).Send(context.Background(), "reindex", 7, 3) {
		t.Fatal("empty base URL should not fail notification")
	}
}

func senderTokenManager(token string) *auth.AgentTokenManager {
	return auth.NewAgentTokenManager(senderTokenStore{token: token}, senderRandom{})
}

type senderTokenStore struct{ token string }

func (s senderTokenStore) Save(context.Context, string, time.Duration) error { return nil }
func (s senderTokenStore) Get(context.Context) (string, error)               { return s.token, nil }

type senderRandom struct{}

func (senderRandom) Generate(int) (string, error) { return "", nil }
