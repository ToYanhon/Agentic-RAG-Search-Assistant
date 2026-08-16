// Package response implements the stable API response envelope.
package response

import (
	"encoding/json"
	"net/http"
)

const (
	BadRequest         = 40000
	Unauthorized       = 40100
	Forbidden          = 40300
	NotFound           = 40400
	Conflict           = 40900
	Internal           = 50000
	Unprocessable      = 42200
	InvalidCredentials = 40101
	TokenExpired       = 40102
	UsernameTaken      = 40901
	EmailTaken         = 40902
	FolderCycle        = 42201
	FileTooLarge       = 41300
	StorageExceeded    = 41301
)

type Envelope struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
	Data    any    `json:"data,omitempty"`
}

func Write(w http.ResponseWriter, status int, code int, message string, data any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(Envelope{Code: code, Message: message, Data: data})
}

func OK(w http.ResponseWriter, data any)      { Write(w, http.StatusOK, 0, "success", data) }
func Created(w http.ResponseWriter, data any) { Write(w, http.StatusCreated, 0, "created", data) }
func Error(w http.ResponseWriter, status, code int, message string) {
	Write(w, status, code, message, nil)
}
