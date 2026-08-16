package httpapi

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/clouddrive-ai/backend/internal/adapter/security"
	"github.com/clouddrive-ai/backend/internal/auth"
)

func TestDownloadAllowsQueryTokenOnly(t *testing.T) {
	random := security.CryptoRandom{}
	tokens := security.NewHS256JWT("test", time.Hour, random)
	jwt, err := tokens.Create(7, "alice")
	if err != nil {
		t.Fatal(err)
	}
	service := auth.NewService(&users{}, tokens, blacklist{}, cache{}, security.BCryptHasher{})
	h := Handler{service: service, agents: auth.NewAgentTokenManager(&tokenStore{}, random)}
	handler := h.protected(func(w http.ResponseWriter, r *http.Request) {
		if userID(r.Context()) != 7 {
			t.Fatalf("user=%d", userID(r.Context()))
		}
		w.WriteHeader(http.StatusNoContent)
	})
	download := httptest.NewRecorder()
	handler.ServeHTTP(download, httptest.NewRequest(http.MethodGet, "/api/v1/files/1/download?token="+jwt, nil))
	if download.Code != http.StatusNoContent {
		t.Fatalf("download status=%d", download.Code)
	}
	nonDownload := httptest.NewRecorder()
	handler.ServeHTTP(nonDownload, httptest.NewRequest(http.MethodGet, "/api/v1/files/1?token="+jwt, nil))
	if nonDownload.Code != http.StatusUnauthorized {
		t.Fatalf("non-download status=%d", nonDownload.Code)
	}
}
