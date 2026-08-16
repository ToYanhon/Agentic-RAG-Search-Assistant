package security

import (
	"strings"
	"testing"
)

func TestAesGCMSecretUsesCompatibleFormat(t *testing.T) {
	secret, err := NewAesGCMSecret("0123456789abcdef0123456789abcdef")
	if err != nil {
		t.Fatal(err)
	}
	encoded, err := secret.Encrypt("api-key")
	if err != nil || !strings.HasPrefix(encoded, "v1:") {
		t.Fatalf("encoded=%q err=%v", encoded, err)
	}
	plain, err := secret.Decrypt(encoded)
	if err != nil || plain != "api-key" {
		t.Fatalf("plain=%q err=%v", plain, err)
	}
}
