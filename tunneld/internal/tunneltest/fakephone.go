package tunneltest

import (
	"context"
	"crypto"
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"

	"github.com/coder/websocket"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/ca"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/wire"
)

// FakePhone is a raw coder/websocket client that completes the /connect CHALLENGE/AUTH handshake and
// bridges inbound REQUEST_* frames to a local http.Handler, streaming the response as RESPONSE_* frames.
type FakePhone struct {
	ws   *websocket.Conn
	done chan struct{}
}

// Dial connects to connectURL (Host set to hostName), authenticates with cert+key, and starts serving.
func Dial(ctx context.Context, connectURL, hostName string, cert *x509.Certificate, key crypto.Signer,
	handler http.Handler) (*FakePhone, error) {
	ws, _, err := websocket.Dial(ctx, connectURL, &websocket.DialOptions{Host: hostName})
	if err != nil {
		return nil, err
	}
	// Largest legal inbound frame = one REQUEST_BODY_CHUNK (ChunkSize body); the library default is
	// just under a full chunk frame. Same constant on both sides of the protocol.
	ws.SetReadLimit(int64(wire.ChunkSize) + 64*1024)

	typ, hdr, _, err := readFrame(ctx, ws)
	if err != nil || typ != wire.CHALLENGE {
		_ = ws.Close(websocket.StatusPolicyViolation, "no challenge")
		return nil, fmt.Errorf("expected CHALLENGE: %w", err)
	}
	var ch struct {
		Nonce []byte `json:"nonce"`
	}
	if err := json.Unmarshal(hdr, &ch); err != nil {
		_ = ws.Close(websocket.StatusPolicyViolation, "bad challenge")
		return nil, err
	}
	digest := sha256.Sum256(append([]byte(ca.ConnectAuthContext), ch.Nonce...))
	sig, err := ecdsa.SignASN1(rand.Reader, key.(*ecdsa.PrivateKey), digest[:])
	if err != nil {
		_ = ws.Close(websocket.StatusInternalError, "sign")
		return nil, err
	}
	auth, _ := json.Marshal(map[string]string{
		"cert":      base64.StdEncoding.EncodeToString(cert.Raw),
		"signature": base64.StdEncoding.EncodeToString(sig),
	})
	if err := writeFrame(ctx, ws, wire.AUTH, auth, nil); err != nil {
		_ = ws.Close(websocket.StatusInternalError, "auth")
		return nil, err
	}
	p := &FakePhone{ws: ws, done: make(chan struct{})}
	go p.serve(handler)
	return p, nil
}

// serve accumulates REQUEST_HEAD + REQUEST_BODY_CHUNKs per reqid, dispatches on REQUEST_END against
// an httptest.ResponseRecorder, and writes RESPONSE_HEAD + chunked RESPONSE_BODY_CHUNK + RESPONSE_END.
func (p *FakePhone) serve(handler http.Handler) {
	defer close(p.done)
	ctx := context.Background()
	type partial struct{ hdr, body []byte }
	pending := map[string]*partial{}
	for {
		typ, hdr, body, err := readFrame(ctx, p.ws)
		if err != nil {
			return
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
				continue // unknown/stale reqid: drop
			}
			delete(pending, reqid)
			_, req := wire.DecodeReqHeader(pr.hdr, pr.body)
			rr := httptest.NewRecorder()
			handler.ServeHTTP(rr, req)
			_ = writeFrame(ctx, p.ws, wire.RESPONSE_HEAD, wire.EncodeRespHeader(reqid, rr.Code, rr.Header()), nil)
			for _, chunk := range chunkBy(rr.Body.Bytes(), wire.ChunkSize) {
				_ = writeFrame(ctx, p.ws, wire.RESPONSE_BODY_CHUNK, wire.EncodeReqIDHeader(reqid), chunk)
			}
			_ = writeFrame(ctx, p.ws, wire.RESPONSE_END, wire.EncodeReqIDHeader(reqid), nil)
		default:
			continue
		}
	}
}

// Close closes the WS and waits for the serve loop to exit.
func (p *FakePhone) Close() error {
	err := p.ws.Close(websocket.StatusNormalClosure, "")
	<-p.done
	return err
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

// chunkBy splits b into ≤n-byte pieces; an empty b yields NO pieces — the canonical encoding of an
// empty body is ZERO body-chunk frames in BOTH directions.
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
