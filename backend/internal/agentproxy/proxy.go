// Package agentproxy 实现到 Python agent 的受控 HTTP 转发。
package agentproxy

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/clouddrive-ai/backend/internal/auth"
	"github.com/clouddrive-ai/backend/internal/llmconfig"
)

var ErrBusy = errors.New("agent busy")

type Config struct {
	BaseURL       string
	HeaderTimeout time.Duration
	MaxConcurrent int
}

type Client struct {
	baseURL       string
	headerTimeout time.Duration
	client        *http.Client
	sem           chan struct{}
	tokens        *auth.AgentTokenManager
	configs       *llmconfig.Service
}

type Response struct {
	Status int
	Header http.Header
	Body   io.ReadCloser
}

func (r Response) AsHTTP() (int, http.Header, io.ReadCloser) { return r.Status, r.Header, r.Body }

func New(config Config, tokens *auth.AgentTokenManager, configs *llmconfig.Service) *Client {
	if config.HeaderTimeout <= 0 {
		config.HeaderTimeout = 60 * time.Second
	}
	if config.MaxConcurrent <= 0 {
		config.MaxConcurrent = 20
	}
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.ForceAttemptHTTP2 = false
	transport.ResponseHeaderTimeout = config.HeaderTimeout
	transport.DialContext = (&net.Dialer{Timeout: 10 * time.Second}).DialContext
	return &Client{
		baseURL:       strings.TrimRight(config.BaseURL, "/"),
		headerTimeout: config.HeaderTimeout,
		client:        &http.Client{Transport: transport},
		sem:           make(chan struct{}, config.MaxConcurrent),
		tokens:        tokens,
		configs:       configs,
	}
}

func (c *Client) Forward(ctx context.Context, method, path, query string, payload []byte, inbound http.Header, userID int64) (Response, error) {
	select {
	case c.sem <- struct{}{}:
	default:
		return Response{}, ErrBusy
	}

	url := c.baseURL + path
	if query != "" {
		url += "?" + query
	}
	req, err := http.NewRequestWithContext(ctx, method, url, bytes.NewReader(payload))
	if err != nil {
		<-c.sem
		return Response{}, err
	}
	for name, values := range inbound {
		if blockedHeader(name) {
			continue
		}
		for _, value := range values {
			req.Header.Add(name, value)
		}
	}
	req.Header.Set("X-User-Id", strconv.FormatInt(userID, 10))
	if token, err := c.tokens.Current(ctx); err == nil && token != "" {
		req.Header.Set("X-Agent-Token", token)
	}
	provider := inbound.Get("X-LLM-Provider")
	if provider == "" {
		provider = "openai"
	}
	req.Header.Set("X-LLM-Provider", provider)
	if resolved := c.configs.Resolve(ctx, userID, provider); resolved.OK {
		if resolved.BaseURL != "" {
			req.Header.Set("X-LLM-Base-URL", resolved.BaseURL)
		}
		if resolved.APIKey != "" {
			req.Header.Set("X-LLM-Key", resolved.APIKey)
		}
		if resolved.Model != "" {
			req.Header.Set("X-LLM-Model", resolved.Model)
		}
	}
	if tavily := c.configs.Resolve(ctx, userID, "tavily"); tavily.OK && tavily.APIKey != "" {
		req.Header.Set("X-Tavily-Key", tavily.APIKey)
	}

	resp, err := c.client.Do(req)
	if err != nil {
		<-c.sem
		return Response{}, err
	}
	return Response{Status: resp.StatusCode, Header: resp.Header, Body: &releaseBody{ReadCloser: resp.Body, release: func() {
		<-c.sem
	}}}, nil
}

func blockedHeader(name string) bool {
	switch strings.ToLower(name) {
	case "connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length", "expect", "x-user-id", "x-agent-token", "x-llm-provider", "x-llm-base-url", "x-llm-key", "x-llm-model", "x-tavily-key":
		return true
	}
	return false
}

type releaseBody struct {
	io.ReadCloser
	release func()
	closed  bool
}

func (b *releaseBody) Close() error {
	if b.closed {
		return nil
	}
	b.closed = true
	err := b.ReadCloser.Close()
	b.release()
	return err
}
