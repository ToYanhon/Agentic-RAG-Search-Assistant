// Package security contains cryptographic adapters for the identity application service.
package security

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"time"

	"github.com/clouddrive-ai/backend/internal/auth"
	"github.com/golang-jwt/jwt/v5"
	"golang.org/x/crypto/bcrypt"
)

type BCryptHasher struct{}

func (BCryptHasher) Hash(value string) (string, error) {
	hash, err := bcrypt.GenerateFromPassword([]byte(value), bcrypt.DefaultCost)
	return string(hash), err
}
func (BCryptHasher) Matches(value, hash string) bool {
	return bcrypt.CompareHashAndPassword([]byte(hash), []byte(value)) == nil
}

type HS256JWT struct {
	secret []byte
	expiry time.Duration
	now    func() time.Time
	random auth.RandomHex
}

func NewHS256JWT(secret string, expiry time.Duration, random auth.RandomHex) *HS256JWT {
	key := sha256.Sum256([]byte(secret))
	return &HS256JWT{secret: key[:], expiry: expiry, now: time.Now, random: random}
}

type claims struct {
	UserID   int64  `json:"user_id"`
	Username string `json:"username"`
	jwt.RegisteredClaims
}

func (j *HS256JWT) Create(userID int64, username string) (string, error) {
	id, err := j.random.Generate(16)
	if err != nil {
		return "", err
	}
	now := j.now()
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims{UserID: userID, Username: username, RegisteredClaims: jwt.RegisteredClaims{ID: id, Subject: username, IssuedAt: jwt.NewNumericDate(now), ExpiresAt: jwt.NewNumericDate(now.Add(j.expiry))}})
	return token.SignedString(j.secret)
}
func (j *HS256JWT) Parse(raw string) (auth.Claims, error) {
	value := claims{}
	token, err := jwt.ParseWithClaims(raw, &value, func(token *jwt.Token) (any, error) {
		if token.Method != jwt.SigningMethodHS256 {
			return nil, fmt.Errorf("unexpected JWT method")
		}
		return j.secret, nil
	})
	if err != nil || !token.Valid || value.UserID <= 0 || value.ExpiresAt == nil {
		return auth.Claims{}, errors.New("invalid or expired token")
	}
	return auth.Claims{UserID: value.UserID, Username: value.Username, ID: value.ID, ExpiresAt: value.ExpiresAt.Time}, nil
}

type CryptoRandom struct{}

func (CryptoRandom) Generate(size int) (string, error) {
	value := make([]byte, size)
	if _, err := rand.Read(value); err != nil {
		return "", err
	}
	return hex.EncodeToString(value), nil
}

// SystemClock 为应用服务提供系统时间。
type SystemClock struct{}

func (SystemClock) Now() time.Time { return time.Now() }
