package auth_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/clouddrive-ai/backend/internal/adapter/security"
	. "github.com/clouddrive-ai/backend/internal/auth"
	"golang.org/x/crypto/bcrypt"
)

type memoryUsers struct {
	byID    map[int64]User
	byName  map[string]User
	byEmail map[string]User
}

func newMemoryUsers() *memoryUsers {
	return &memoryUsers{byID: map[int64]User{}, byName: map[string]User{}, byEmail: map[string]User{}}
}
func (m *memoryUsers) FindByID(_ context.Context, id int64) (User, error) {
	user, ok := m.byID[id]
	if !ok {
		return User{}, ErrNotFound
	}
	return user, nil
}
func (m *memoryUsers) FindByUsername(_ context.Context, username string) (User, error) {
	user, ok := m.byName[username]
	if !ok {
		return User{}, ErrNotFound
	}
	return user, nil
}
func (m *memoryUsers) FindByEmail(_ context.Context, email string) (User, error) {
	user, ok := m.byEmail[email]
	if !ok {
		return User{}, ErrNotFound
	}
	return user, nil
}
func (m *memoryUsers) Create(_ context.Context, username, email, password string) error {
	if _, ok := m.byName[username]; ok {
		return ErrUsernameTaken
	}
	user := User{ID: int64(len(m.byID) + 1), Username: username, Email: email, Password: password, StorageLimit: 1073741824, CreatedAt: time.Date(2026, 8, 16, 12, 0, 0, 0, time.UTC)}
	m.byID[user.ID] = user
	m.byName[username] = user
	m.byEmail[email] = user
	return nil
}
func (m *memoryUsers) UpdateUsername(_ context.Context, id int64, username string) error {
	user, ok := m.byID[id]
	if !ok {
		return ErrNotFound
	}
	delete(m.byName, user.Username)
	user.Username = username
	m.byID[id] = user
	m.byName[username] = user
	return nil
}
func (m *memoryUsers) UpdatePassword(_ context.Context, id int64, password string) error {
	user, ok := m.byID[id]
	if !ok {
		return ErrNotFound
	}
	user.Password = password
	m.byID[id] = user
	m.byName[user.Username] = user
	return nil
}

type memoryCache struct{ profiles map[int64]Profile }

func (m *memoryCache) Get(_ context.Context, id int64) (Profile, bool, error) {
	value, ok := m.profiles[id]
	return value, ok, nil
}
func (m *memoryCache) Set(_ context.Context, value Profile, _ time.Duration) error {
	m.profiles[value.ID] = value
	return nil
}
func (m *memoryCache) Delete(_ context.Context, id int64) error { delete(m.profiles, id); return nil }

type memoryBlacklist struct{ values map[string]bool }

func (m *memoryBlacklist) Add(_ context.Context, jti string, _ time.Duration) error {
	m.values[jti] = true
	return nil
}
func (m *memoryBlacklist) Contains(_ context.Context, jti string) (bool, error) {
	return m.values[jti], nil
}
func serviceForTest() (*Service, *memoryUsers) {
	users := newMemoryUsers()
	cache := &memoryCache{profiles: map[int64]Profile{}}
	blacklist := &memoryBlacklist{values: map[string]bool{}}
	random := security.CryptoRandom{}
	return NewService(users, security.NewHS256JWT("test-secret", time.Hour, random), blacklist, cache, security.BCryptHasher{}), users
}

func TestRegisterHashesPasswordAndDetectsDuplicates(t *testing.T) {
	service, users := serviceForTest()
	if err := service.Register(context.Background(), "alice", "alice@example.com", "password"); err != nil {
		t.Fatal(err)
	}
	user, err := users.FindByUsername(context.Background(), "alice")
	if err != nil {
		t.Fatal(err)
	}
	if user.Password == "password" || bcrypt.CompareHashAndPassword([]byte(user.Password), []byte("password")) != nil {
		t.Fatal("password was not bcrypt hashed")
	}
	if err := service.Register(context.Background(), "alice", "other@example.com", "password"); !errors.Is(err, ErrUsernameTaken) {
		t.Fatalf("got %v", err)
	}
	if err := service.Register(context.Background(), "other", "alice@example.com", "password"); !errors.Is(err, ErrEmailTaken) {
		t.Fatalf("got %v", err)
	}
}

func TestLoginLogoutRevokesJWT(t *testing.T) {
	service, _ := serviceForTest()
	if err := service.Register(context.Background(), "alice", "alice@example.com", "password"); err != nil {
		t.Fatal(err)
	}
	token, profile, err := service.Login(context.Background(), "alice", "password")
	if err != nil || profile.Username != "alice" {
		t.Fatalf("login = %v, %v", profile, err)
	}
	if _, err := service.Authenticate(context.Background(), token); err != nil {
		t.Fatal(err)
	}
	if err := service.Logout(context.Background(), token); err != nil {
		t.Fatal(err)
	}
	if _, err := service.Authenticate(context.Background(), token); err == nil {
		t.Fatal("revoked token was accepted")
	}
}

func TestProfileFallsBackWhenCacheIsUnavailable(t *testing.T) {
	service, users := serviceForTest()
	if err := service.Register(context.Background(), "alice", "alice@example.com", "password"); err != nil {
		t.Fatal(err)
	}
	service = NewService(users, security.NewHS256JWT("test-secret", time.Hour, security.CryptoRandom{}), &memoryBlacklist{values: map[string]bool{}}, failingCache{}, security.BCryptHasher{})
	profile, err := service.Profile(context.Background(), 1)
	if err != nil || profile.Username != "alice" {
		t.Fatalf("profile = %#v, %v", profile, err)
	}
}

func TestJWTUsesCompatibleClaims(t *testing.T) {
	tokens := security.NewHS256JWT("shared-secret", time.Hour, security.CryptoRandom{})
	raw, err := tokens.Create(42, "alice")
	if err != nil {
		t.Fatal(err)
	}
	claims, err := tokens.Parse(raw)
	if err != nil {
		t.Fatal(err)
	}
	if claims.UserID != 42 || claims.Username != "alice" || len(claims.ID) != 32 {
		t.Fatalf("claims = %#v", claims)
	}
}

func TestAgentTokenHasExpectedLength(t *testing.T) {
	store := &agentStore{}
	manager := NewAgentTokenManager(store, security.CryptoRandom{})
	if err := manager.Rotate(context.Background()); err != nil {
		t.Fatal(err)
	}
	if len(store.value) != 64 || !manager.Validate(context.Background(), store.value) {
		t.Fatalf("unexpected token %q", store.value)
	}
}

type agentStore struct{ value string }

func (s *agentStore) Save(_ context.Context, value string, ttl time.Duration) error {
	if ttl != 30*time.Minute {
		return errors.New("unexpected ttl")
	}
	s.value = value
	return nil
}
func (s *agentStore) Get(context.Context) (string, error) { return s.value, nil }

type failingCache struct{}

func (failingCache) Get(context.Context, int64) (Profile, bool, error) {
	return Profile{}, false, errors.New("redis unavailable")
}
func (failingCache) Set(context.Context, Profile, time.Duration) error {
	return errors.New("redis unavailable")
}
func (failingCache) Delete(context.Context, int64) error { return errors.New("redis unavailable") }
