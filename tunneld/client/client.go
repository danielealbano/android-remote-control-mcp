// Package client is a reusable Go implementation of the phone side: enroll (CSR → signed cert),
// connect (application-layer challenge-response over the WebSocket — NO TLS client cert), and bridge
// each forwarded request to a local HTTP backend. It shares the wire + ca packages with the server
// so there is zero protocol drift. Used by the in-process server test and the containerized e2e.
package client

import (
	"bytes"
	"context"
	"crypto"
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"time"

	"github.com/coder/websocket"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/ca"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/wire"
)

const (
	initialBackoff = 100 * time.Millisecond
	maxBackoff     = 5 * time.Second
)

// Client carries optional HTTP settings. Headers is normally empty (a proxy supplies the client-IP
// header); tests against a proxy-less server inject it here.
type Client struct {
	HTTP    *http.Client
	Headers http.Header
}

// New returns a Client using http.DefaultClient.
func New() *Client { return &Client{} }

func (c *Client) httpClient() *http.Client {
	if c.HTTP != nil {
		return c.HTTP
	}
	return http.DefaultClient
}

// Enroll builds a CSR from key and POSTs it to enrollURL, returning the signed certificate PEM and
// the assigned tunnel name.
func (c *Client) Enroll(ctx context.Context, enrollURL string, key *ecdsa.PrivateKey) (certPEM []byte, name string, err error) {
	csrDER, err := x509.CreateCertificateRequest(rand.Reader, &x509.CertificateRequest{}, key)
	if err != nil {
		return nil, "", err
	}
	csrPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE REQUEST", Bytes: csrDER})

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, enrollURL, bytes.NewReader(csrPEM))
	if err != nil {
		return nil, "", err
	}
	c.applyHeaders(req)
	resp, err := c.httpClient().Do(req)
	if err != nil {
		return nil, "", err
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return nil, "", fmt.Errorf("enroll failed: %d %s", resp.StatusCode, body)
	}
	var out struct {
		Name           string `json:"name"`
		CertificatePEM string `json:"certificate_pem"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return nil, "", err
	}
	return []byte(out.CertificatePEM), out.Name, nil
}

// Connect dials connectURL (Host set to hostName), completes the challenge-response, then bridges
// forwarded requests to backend until the connection drops or ctx is done. It returns the drop error.
func (c *Client) Connect(ctx context.Context, connectURL, hostName string, cert *x509.Certificate, key crypto.Signer, backend http.Handler) error {
	ws, _, err := websocket.Dial(ctx, connectURL, &websocket.DialOptions{Host: hostName, HTTPHeader: c.Headers})
	if err != nil {
		return err
	}
	defer func() { _ = ws.Close(websocket.StatusNormalClosure, "") }()
	ws.SetReadLimit(int64(wire.ChunkSize) + 64*1024)

	if err := handshake(ctx, ws, cert, key); err != nil {
		return err
	}
	return bridge(ctx, ws, backend)
}

// Serve runs Connect in a loop, reconnecting with bounded exponential backoff until ctx is done.
func (c *Client) Serve(ctx context.Context, connectURL, hostName string, cert *x509.Certificate, key crypto.Signer, backend http.Handler) error {
	backoff := initialBackoff
	for {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		start := time.Now()
		_ = c.Connect(ctx, connectURL, hostName, cert, key, backend)
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if time.Since(start) > time.Second {
			backoff = initialBackoff // the connection was established for a while; reset
		}
		t := time.NewTimer(backoff)
		select {
		case <-ctx.Done():
			t.Stop()
			return ctx.Err()
		case <-t.C:
		}
		if backoff *= 2; backoff > maxBackoff {
			backoff = maxBackoff
		}
	}
}

func (c *Client) applyHeaders(req *http.Request) {
	for k, vs := range c.Headers {
		for _, v := range vs {
			req.Header.Add(k, v)
		}
	}
}

// handshake reads the CHALLENGE and replies with AUTH (base64 DER cert + ECDSA-P256 signature over
// ConnectAuthContext ‖ nonce).
func handshake(ctx context.Context, ws *websocket.Conn, cert *x509.Certificate, key crypto.Signer) error {
	typ, hdr, _, err := readFrame(ctx, ws)
	if err != nil {
		return err
	}
	if typ != wire.CHALLENGE {
		return errors.New("expected CHALLENGE frame")
	}
	var ch struct {
		Nonce []byte `json:"nonce"`
	}
	if err := json.Unmarshal(hdr, &ch); err != nil {
		return err
	}
	ecKey, ok := key.(*ecdsa.PrivateKey)
	if !ok {
		return errors.New("client key is not ECDSA")
	}
	digest := sha256.Sum256(append([]byte(ca.ConnectAuthContext), ch.Nonce...))
	sig, err := ecdsa.SignASN1(rand.Reader, ecKey, digest[:])
	if err != nil {
		return err
	}
	auth, _ := json.Marshal(map[string]string{
		"cert":      base64.StdEncoding.EncodeToString(cert.Raw),
		"signature": base64.StdEncoding.EncodeToString(sig),
	})
	return writeFrame(ctx, ws, wire.AUTH, auth, nil)
}

// bridge accumulates REQUEST_HEAD + REQUEST_BODY_CHUNKs per reqid, dispatches on REQUEST_END against
// backend, and streams the response back as RESPONSE_HEAD + chunked RESPONSE_BODY_CHUNK + RESPONSE_END.
func bridge(ctx context.Context, ws *websocket.Conn, backend http.Handler) error {
	type partial struct{ hdr, body []byte }
	pending := map[string]*partial{}
	for {
		typ, hdr, body, err := readFrame(ctx, ws)
		if err != nil {
			return err
		}
		switch typ {
		case wire.REQUEST_HEAD:
			pending[wire.FrameReqID(hdr)] = &partial{hdr: hdr}
		case wire.REQUEST_BODY_CHUNK:
			if pr := pending[wire.FrameReqID(hdr)]; pr != nil {
				pr.body = append(pr.body, body...)
			}
		case wire.REQUEST_END:
			reqid := wire.FrameReqID(hdr)
			pr := pending[reqid]
			if pr == nil {
				continue
			}
			delete(pending, reqid)
			_, req := wire.DecodeReqHeader(pr.hdr, pr.body)
			rr := httptest.NewRecorder()
			backend.ServeHTTP(rr, req)
			if err := writeFrame(ctx, ws, wire.RESPONSE_HEAD, wire.EncodeRespHeader(reqid, rr.Code, rr.Header()), nil); err != nil {
				return err
			}
			for _, chunk := range chunkBy(rr.Body.Bytes(), wire.ChunkSize) {
				if err := writeFrame(ctx, ws, wire.RESPONSE_BODY_CHUNK, wire.EncodeReqIDHeader(reqid), chunk); err != nil {
					return err
				}
			}
			if err := writeFrame(ctx, ws, wire.RESPONSE_END, wire.EncodeReqIDHeader(reqid), nil); err != nil {
				return err
			}
		default:
			continue
		}
	}
}

func readFrame(ctx context.Context, ws *websocket.Conn) (wire.FrameType, []byte, []byte, error) {
	_, data, err := ws.Read(ctx)
	if err != nil {
		return 0, nil, nil, err
	}
	return wire.DecodeFrame(data)
}

func writeFrame(ctx context.Context, ws *websocket.Conn, t wire.FrameType, header, body []byte) error {
	return ws.Write(ctx, websocket.MessageBinary, wire.EncodeFrame(t, header, body))
}

func chunkBy(b []byte, n int) [][]byte {
	var out [][]byte
	for len(b) > n {
		out = append(out, b[:n])
		b = b[n:]
	}
	if len(b) > 0 {
		out = append(out, b)
	}
	return out
}
