// Package auth holds the framework-independent M1 identity and access use cases.
package auth

import (
	"context"
	"errors"
	"strings"
	"time"
)

var (
	ErrInvalidCredentials = errors.New("invalid username or password")
	ErrUsernameTaken      = errors.New("username already exists")
	ErrEmailTaken         = errors.New("email already exists")
	ErrDuplicateUser      = errors.New("duplicate user")
	ErrNotFound           = errors.New("user not found")
	ErrWrongPassword      = errors.New("wrong password")
)

type User struct {
	ID           int64
	Username     string
	Email        string
	Password     string
	StorageUsed  int64
	StorageLimit int64
	CreatedAt    time.Time
}

type Profile struct {
	ID           int64  `json:"id"`
	Username     string `json:"username"`
	Email        string `json:"email"`
	StorageUsed  int64  `json:"storage_used"`
	StorageLimit int64  `json:"storage_limit"`
	CreatedAt    string `json:"created_at"`
}

func profileOf(user User) Profile {
	return Profile{ID: user.ID, Username: user.Username, Email: user.Email, StorageUsed: user.StorageUsed, StorageLimit: user.StorageLimit, CreatedAt: user.CreatedAt.Format("2006-01-02T15:04:05Z")}
}

type UserRepository interface {
	FindByID(context.Context, int64) (User, error)
	FindByUsername(context.Context, string) (User, error)
	FindByEmail(context.Context, string) (User, error)
	Create(context.Context, string, string, string) error
	UpdateUsername(context.Context, int64, string) error
	UpdatePassword(context.Context, int64, string) error
}

type ProfileCache interface {
	Get(context.Context, int64) (Profile, bool, error)
	Set(context.Context, Profile, time.Duration) error
	Delete(context.Context, int64) error
}

type Blacklist interface {
	Add(context.Context, string, time.Duration) error
	Contains(context.Context, string) (bool, error)
}

type Claims struct {
	UserID    int64
	Username  string
	ID        string
	ExpiresAt time.Time
}

// PasswordHasher and TokenService are application ports. Their adapters own crypto libraries.
type PasswordHasher interface {
	Hash(string) (string, error)
	Matches(string, string) bool
}
type TokenService interface {
	Create(int64, string) (string, error)
	Parse(string) (Claims, error)
}

type Service struct {
	users     UserRepository
	tokens    TokenService
	blacklist Blacklist
	cache     ProfileCache
	passwords PasswordHasher
}

func NewService(users UserRepository, tokens TokenService, blacklist Blacklist, cache ProfileCache, passwords PasswordHasher) *Service {
	return &Service{users: users, tokens: tokens, blacklist: blacklist, cache: cache, passwords: passwords}
}

func (s *Service) Register(ctx context.Context, username, email, password string) error {
	if _, err := s.users.FindByUsername(ctx, username); err == nil {
		return ErrUsernameTaken
	} else if !errors.Is(err, ErrNotFound) {
		return err
	}
	if _, err := s.users.FindByEmail(ctx, email); err == nil {
		return ErrEmailTaken
	} else if !errors.Is(err, ErrNotFound) {
		return err
	}
	hash, err := s.passwords.Hash(password)
	if err != nil {
		return err
	}
	return s.users.Create(ctx, username, email, hash)
}

func (s *Service) Login(ctx context.Context, username, password string) (string, Profile, error) {
	user, err := s.users.FindByUsername(ctx, username)
	if err != nil || !s.passwords.Matches(password, user.Password) {
		return "", Profile{}, ErrInvalidCredentials
	}
	token, err := s.tokens.Create(user.ID, user.Username)
	return token, profileOf(user), err
}

func (s *Service) Logout(ctx context.Context, raw string) error {
	claims, err := s.tokens.Parse(raw)
	if err != nil || claims.ID == "" {
		return errors.New("unauthorized")
	}
	ttl := time.Until(claims.ExpiresAt)
	if ttl <= 0 {
		ttl = time.Second
	}
	return s.blacklist.Add(ctx, claims.ID, ttl)
}

func (s *Service) Authenticate(ctx context.Context, raw string) (Claims, error) {
	claims, err := s.tokens.Parse(raw)
	if err != nil {
		return Claims{}, err
	}
	blocked, err := s.blacklist.Contains(ctx, claims.ID)
	if err != nil {
		return Claims{}, err
	}
	if blocked {
		return Claims{}, errors.New("token revoked")
	}
	return claims, nil
}

func (s *Service) Profile(ctx context.Context, userID int64) (Profile, error) {
	if profile, ok, err := s.cache.Get(ctx, userID); err == nil && ok {
		return profile, nil
	}
	user, err := s.users.FindByID(ctx, userID)
	if err != nil {
		return Profile{}, err
	}
	profile := profileOf(user)
	_ = s.cache.Set(ctx, profile, 5*time.Minute)
	return profile, nil
}

func (s *Service) UpdateUsername(ctx context.Context, userID int64, username string) (Profile, error) {
	if strings.TrimSpace(username) == "" {
		return Profile{}, errors.New("nothing to update")
	}
	if existing, err := s.users.FindByUsername(ctx, username); err == nil && existing.ID != userID {
		return Profile{}, ErrUsernameTaken
	} else if err != nil && !errors.Is(err, ErrNotFound) {
		return Profile{}, err
	}
	if err := s.users.UpdateUsername(ctx, userID, username); err != nil {
		return Profile{}, err
	}
	_ = s.cache.Delete(ctx, userID)
	return s.Profile(ctx, userID)
}

func (s *Service) ChangePassword(ctx context.Context, userID int64, oldPassword, newPassword string) error {
	user, err := s.users.FindByID(ctx, userID)
	if err != nil {
		return err
	}
	if !s.passwords.Matches(oldPassword, user.Password) {
		return ErrWrongPassword
	}
	hash, err := s.passwords.Hash(newPassword)
	if err != nil {
		return err
	}
	if err := s.users.UpdatePassword(ctx, userID, hash); err != nil {
		return err
	}
	_ = s.cache.Delete(ctx, userID)
	return nil
}

type AgentTokenStore interface {
	Save(context.Context, string, time.Duration) error
	Get(context.Context) (string, error)
}
type RandomHex interface{ Generate(int) (string, error) }
type AgentTokenManager struct {
	store  AgentTokenStore
	random RandomHex
}

func NewAgentTokenManager(store AgentTokenStore, random RandomHex) *AgentTokenManager {
	return &AgentTokenManager{store: store, random: random}
}
func (m *AgentTokenManager) Rotate(ctx context.Context) error {
	token, err := m.random.Generate(32)
	if err != nil {
		return err
	}
	return m.store.Save(ctx, token, 30*time.Minute)
}
func (m *AgentTokenManager) Validate(ctx context.Context, value string) bool {
	stored, err := m.store.Get(ctx)
	return err == nil && value != "" && value == stored
}

// Current 返回 Go 写入共享 agent token 键的当前值。
func (m *AgentTokenManager) Current(ctx context.Context) (string, error) {
	return m.store.Get(ctx)
}

// Run rotates the agent token at a fixed 15 minute cadence.
func (m *AgentTokenManager) Run(ctx context.Context) {
	ticker := time.NewTicker(15 * time.Minute)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			_ = m.Rotate(ctx)
		}
	}
}
