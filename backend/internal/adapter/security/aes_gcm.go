package security

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"strings"
)

// AesGCMSecret 使用 v1:base64(nonce):base64(cipher) 格式。
type AesGCMSecret struct{ key []byte }

func NewAesGCMSecret(master string) (*AesGCMSecret, error) {
	if len(master) < 32 {
		return nil, errors.New("llm encryption key must be at least 32 bytes")
	}
	key := sha256.Sum256([]byte(master))
	return &AesGCMSecret{key: key[:]}, nil
}

func (s *AesGCMSecret) Encrypt(plain string) (string, error) {
	block, err := aes.NewCipher(s.key)
	if err != nil {
		return "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err = rand.Read(nonce); err != nil {
		return "", err
	}
	sealed := gcm.Seal(nil, nonce, []byte(plain), nil)
	return "v1:" + base64.StdEncoding.EncodeToString(nonce) + ":" + base64.StdEncoding.EncodeToString(sealed), nil
}
func (s *AesGCMSecret) Decrypt(encoded string) (string, error) {
	parts := strings.SplitN(encoded, ":", 3)
	if len(parts) != 3 || parts[0] != "v1" {
		return "", errors.New("invalid ciphertext")
	}
	nonce, err := base64.StdEncoding.DecodeString(parts[1])
	if err != nil {
		return "", err
	}
	sealed, err := base64.StdEncoding.DecodeString(parts[2])
	if err != nil {
		return "", err
	}
	block, err := aes.NewCipher(s.key)
	if err != nil {
		return "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}
	if len(nonce) != gcm.NonceSize() {
		return "", errors.New("invalid nonce")
	}
	plain, err := gcm.Open(nil, nonce, sealed, nil)
	if err != nil {
		return "", err
	}
	return string(plain), nil
}
