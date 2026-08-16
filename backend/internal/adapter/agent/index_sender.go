// Package agent 实现 Agent 索引通知的 HTTP 出站 adapter。
package agent

import (
	"context"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/clouddrive-ai/backend/internal/auth"
)

type IndexSender struct {
	baseURL string
	tokens  *auth.AgentTokenManager
	client  *http.Client
}

func NewIndexSender(baseURL string, tokens *auth.AgentTokenManager) *IndexSender {
	transport := http.DefaultTransport.(*http.Transport).Clone()
	// 明文 uvicorn 不支持 HTTP/2 的 h2c upgrade。
	transport.ForceAttemptHTTP2 = false
	transport.DialContext = (&net.Dialer{Timeout: 10 * time.Second}).DialContext
	return &IndexSender{baseURL: strings.TrimRight(baseURL, "/"), tokens: tokens, client: &http.Client{Transport: transport, Timeout: 10 * time.Second}}
}

func (s *IndexSender) Send(ctx context.Context, kind string, fileID, ownerID int64) bool {
	if s.baseURL == "" {
		return true
	}
	token, err := s.tokens.Current(ctx)
	if err != nil {
		return false
	}
	method := http.MethodPost
	if kind == "unindex" {
		method = http.MethodDelete
	}
	req, err := http.NewRequestWithContext(ctx, method, s.baseURL+"/index/"+strconv.FormatInt(fileID, 10), nil)
	if err != nil {
		return false
	}
	req.Header.Set("X-User-Id", strconv.FormatInt(ownerID, 10))
	if token != "" {
		req.Header.Set("X-Agent-Token", token)
	}
	resp, err := s.client.Do(req)
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	return resp.StatusCode >= http.StatusOK && resp.StatusCode < http.StatusMultipleChoices
}
