package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/clouddrive-ai/backend/internal/adapter/security"
	"github.com/clouddrive-ai/backend/internal/auth"
	"golang.org/x/crypto/bcrypt"
)

type users struct{ user auth.User }

func (u *users) FindByID(context.Context, int64) (auth.User, error)        { return u.user, nil }
func (u *users) FindByUsername(context.Context, string) (auth.User, error) { return u.user, nil }
func (u *users) FindByEmail(context.Context, string) (auth.User, error) {
	return auth.User{}, auth.ErrNotFound
}
func (u *users) Create(context.Context, string, string, string) error { return nil }
func (u *users) UpdateUsername(context.Context, int64, string) error  { return nil }
func (u *users) UpdatePassword(context.Context, int64, string) error  { return nil }

type cache struct{}

func (cache) Get(context.Context, int64) (auth.Profile, bool, error) {
	return auth.Profile{}, false, nil
}
func (cache) Set(context.Context, auth.Profile, time.Duration) error { return nil }
func (cache) Delete(context.Context, int64) error                    { return nil }

type blacklist struct{}

func (blacklist) Add(context.Context, string, time.Duration) error { return nil }
func (blacklist) Contains(context.Context, string) (bool, error)   { return false, nil }

type tokenStore struct{ token string }

func (s *tokenStore) Save(context.Context, string, time.Duration) error { return nil }
func (s *tokenStore) Get(context.Context) (string, error)               { return s.token, nil }

func TestHealthAndUnauthorizedEnvelope(t *testing.T) {
	password, _ := bcrypt.GenerateFromPassword([]byte("password"), bcrypt.DefaultCost)
	users := &users{user: auth.User{ID: 1, Username: "alice", Email: "alice@example.com", Password: string(password), StorageLimit: 1, CreatedAt: time.Now()}}
	random := security.CryptoRandom{}
	service := auth.NewService(users, security.NewHS256JWT("test", time.Hour, random), blacklist{}, cache{}, security.BCryptHasher{})
	handler := New(service, auth.NewAgentTokenManager(&tokenStore{token: "agent"}, random))
	health := httptest.NewRecorder()
	handler.ServeHTTP(health, httptest.NewRequest(http.MethodGet, "/health", nil))
	if health.Code != http.StatusOK || health.Body.String() != "{\"status\":\"ok\"}\n" {
		t.Fatalf("health = %d %s", health.Code, health.Body.String())
	}
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, httptest.NewRequest(http.MethodGet, "/api/v1/auth/profile", nil))
	if response.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d", response.Code)
	}
	var envelope map[string]any
	if err := json.Unmarshal(response.Body.Bytes(), &envelope); err != nil {
		t.Fatal(err)
	}
	if envelope["code"] != float64(40100) {
		t.Fatalf("envelope = %#v", envelope)
	}
	malformed := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "/api/v1/auth/profile", nil)
	request.Header.Set("Authorization", "Token malformed")
	handler.ServeHTTP(malformed, request)
	if err := json.Unmarshal(malformed.Body.Bytes(), &envelope); err != nil {
		t.Fatal(err)
	}
	if malformed.Code != http.StatusUnauthorized || envelope["code"] != float64(40100) {
		t.Fatalf("malformed bearer = %d %#v", malformed.Code, envelope)
	}
}

func TestLoginReturnsSnakeCaseEnvelope(t *testing.T) {
	password, _ := bcrypt.GenerateFromPassword([]byte("password"), bcrypt.DefaultCost)
	users := &users{user: auth.User{ID: 1, Username: "alice", Email: "alice@example.com", Password: string(password), StorageLimit: 1, CreatedAt: time.Now()}}
	random := security.CryptoRandom{}
	service := auth.NewService(users, security.NewHS256JWT("test", time.Hour, random), blacklist{}, cache{}, security.BCryptHasher{})
	handler := New(service, auth.NewAgentTokenManager(&tokenStore{}, random))
	request := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login", bytes.NewBufferString(`{"username":"alice","password":"password"}`))
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("status = %d body=%s", response.Code, response.Body.String())
	}
	if !bytes.Contains(response.Body.Bytes(), []byte(`"storage_used"`)) {
		t.Fatalf("response is not snake_case: %s", response.Body.String())
	}
}

func TestRegisterRejectsInvalidEmail(t *testing.T) {
	random := security.CryptoRandom{}
	service := auth.NewService(&users{}, security.NewHS256JWT("test", time.Hour, random), blacklist{}, cache{}, security.BCryptHasher{})
	handler := New(service, auth.NewAgentTokenManager(&tokenStore{}, random))
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, httptest.NewRequest(http.MethodPost, "/api/v1/auth/register", bytes.NewBufferString(`{"username":"alice","email":"not-an-email","password":"password"}`)))
	if response.Code != http.StatusBadRequest {
		t.Fatalf("status = %d body=%s", response.Code, response.Body.String())
	}
}

func TestAuthRejectsBlankRequiredValues(t *testing.T) {
	random := security.CryptoRandom{}
	service := auth.NewService(&users{}, security.NewHS256JWT("test", time.Hour, random), blacklist{}, cache{}, security.BCryptHasher{})
	handler := New(service, auth.NewAgentTokenManager(&tokenStore{}, random))
	cases := []struct {
		method string
		path   string
		body   string
	}{
		{http.MethodPost, "/api/v1/auth/register", `{"username":"   ","email":"alice@example.com","password":"password"}`},
		{http.MethodPost, "/api/v1/auth/login", `{"username":"   ","password":"password"}`},
	}
	for _, test := range cases {
		request := httptest.NewRequest(test.method, test.path, bytes.NewBufferString(test.body))
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, request)
		if response.Code != http.StatusBadRequest {
			t.Fatalf("%s %s status = %d body=%s", test.method, test.path, response.Code, response.Body.String())
		}
	}
}
