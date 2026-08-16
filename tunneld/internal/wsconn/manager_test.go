package wsconn

import (
	"bytes"
	"context"
	"encoding/base64"
	"fmt"
	"io"
	"net/http"
	"sync"
	"testing"
	"time"

	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/config"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/tunneltest"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/wire"
)

const tName = "abcname2345" // 11-char valid DNS label

func routeReq(name, reqid, method, path string, body []byte) *wire.ReqEnvelope {
	return &wire.ReqEnvelope{ReqID: reqid, TunnelName: name, Method: method, Path: path, Host: name + ".example.test", Body: body}
}

func waitRec(t *testing.T, rec *tunneltest.Recorder, kind, reason string, want int) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if rec.Count(kind, reason) >= want {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("recorder never reached %s/%s >= %d (got %d)", kind, reason, want, rec.Count(kind, reason))
}

func TestConnectNonUpgrade426(t *testing.T) {
	h := newHarness(t, 0, nil)
	resp, err := http.Get(h.srv.URL + "/connect")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusUpgradeRequired {
		t.Errorf("status = %d, want 426", resp.StatusCode)
	}
}

func TestConnectMissingClientIP400(t *testing.T) {
	h := newHarness(t, 0, nil)
	h.clientIP = "" // proxy injects nothing
	resp, err := http.Get(h.srv.URL + "/connect")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("status = %d, want 400", resp.StatusCode)
	}
	if h.rec.Count("reject", "missing_client_ip") != 1 {
		t.Error("missing_client_ip not recorded")
	}
}

func TestConnectRejectsBannedIP(t *testing.T) {
	h := newHarness(t, 0, nil)
	h.clientIP = "10.20.30.40"
	h.loadBans("ip 10.20.30.40\n")
	resp, err := http.Get(h.srv.URL + "/connect")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusForbidden {
		t.Errorf("status = %d, want 403", resp.StatusCode)
	}
	if h.rec.Count("reject", "banned_ip") != 1 {
		t.Error("banned_ip not recorded")
	}
}

func TestConnectBindsAndServes(t *testing.T) {
	h := newHarness(t, 0, nil)
	phone := h.connectPhone(tName, okHandler("pong"))
	defer func() { _ = phone.Close() }()
	resp := h.mgr.RouteLocal(context.Background(), routeReq(tName, "r1", "GET", "/mcp", nil))
	if resp.Status != 200 || string(resp.Body) != "pong" {
		t.Errorf("resp = %+v body=%q", resp, resp.Body)
	}
	if h.rec.Count("wsconnect", "") != 1 {
		t.Error("WSConnect not recorded")
	}
}

func TestRecorderWSLifecycleAndBytes(t *testing.T) {
	h := newHarness(t, 0, nil)
	phone := h.connectPhone(tName, okHandler("hello-response"))
	resp := h.mgr.RouteLocal(context.Background(), routeReq(tName, "r1", "POST", "/mcp", []byte("request-body")))
	if resp.Status != 200 {
		t.Fatalf("status %d", resp.Status)
	}
	if h.rec.Count("bytes", "") == 0 {
		t.Error("no byte accounting recorded")
	}
	_ = phone.Close()
	waitRec(t, h.rec, "wsdisconnect", "", 1)
}

func TestConnectRejectsBadPossession(t *testing.T) {
	h := newHarness(t, 0, nil)
	cert, _ := h.issue(tName)
	_, wrongKey := h.issue(tName) // a different key
	ws, nonce := rawDial(t, h.wsURL(), h.host(tName))
	defer func() { _ = ws.Close(1000, "") }()
	sendAuth(t, ws, base64.StdEncoding.EncodeToString(cert.Raw), signNonce(wrongKey, nonce))
	waitRec(t, h.rec, "reject", "connect_auth_failed", 1)
	if _, _, ok, _ := h.reg.Lookup(context.Background(), tName); ok {
		t.Error("must not bind on bad possession signature")
	}
}

func TestConnectRejectsCNMismatch(t *testing.T) {
	h := newHarness(t, 0, nil)
	cert, key := h.issue(tName) // CN = tName
	ws, nonce := rawDial(t, h.wsURL(), h.host("differentname9"))
	defer func() { _ = ws.Close(1000, "") }()
	sendAuth(t, ws, base64.StdEncoding.EncodeToString(cert.Raw), signNonce(key, nonce))
	waitRec(t, h.rec, "reject", "connect_auth_failed", 1)
	if _, _, ok, _ := h.reg.Lookup(context.Background(), tName); ok {
		t.Error("must not bind when CN != host")
	}
}

func TestConnectRejectsAuthTimeout(t *testing.T) {
	h := newHarness(t, 0, func(c *config.ServeCmd) { c.ConnectAuthTimeout = 200 * time.Millisecond })
	ws, _ := rawDial(t, h.wsURL(), h.host(tName))
	defer func() { _ = ws.Close(1000, "") }()
	// never send AUTH → server times out
	waitRec(t, h.rec, "reject", "connect_auth_failed", 1)
	if _, _, ok, _ := h.reg.Lookup(context.Background(), tName); ok {
		t.Error("must not bind on auth timeout")
	}
}

func TestConnectRefusesBannedTunnel(t *testing.T) {
	h := newHarness(t, 0, nil)
	h.loadBans("tunnel-name " + tName + "\n")
	cert, key := h.issue(tName)
	ws, nonce := rawDial(t, h.wsURL(), h.host(tName))
	defer func() { _ = ws.Close(1000, "") }()
	sendAuth(t, ws, base64.StdEncoding.EncodeToString(cert.Raw), signNonce(key, nonce))
	waitRec(t, h.rec, "reject", "banned_tunnel_name", 1)
	if _, _, ok, _ := h.reg.Lookup(context.Background(), tName); ok {
		t.Error("must not bind a banned tunnel name")
	}
}

func TestFingerprintConflictRejected(t *testing.T) {
	h := newHarness(t, 0, nil)
	phone := h.connectPhone(tName, okHandler("ok"))
	defer func() { _ = phone.Close() }()
	// Second connect, SAME name, DIFFERENT cert (different fingerprint).
	cert2, key2 := h.issue(tName)
	ws, nonce := rawDial(t, h.wsURL(), h.host(tName))
	defer func() { _ = ws.Close(1000, "") }()
	sendAuth(t, ws, base64.StdEncoding.EncodeToString(cert2.Raw), signNonce(key2, nonce))
	waitRec(t, h.rec, "reject", "fingerprint_conflict", 1)
}

func TestEvictDropsLiveBannedTunnel(t *testing.T) {
	h := newHarness(t, 0, nil)
	phone := h.connectPhone(tName, okHandler("ok"))
	defer func() { _ = phone.Close() }()
	h.loadBans("tunnel-name " + tName + "\n")
	h.mgr.EvictBanned(h.ban)
	waitRec(t, h.rec, "wsdisconnect", "banned_tunnel_name", 1)
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if _, _, ok, _ := h.reg.Lookup(context.Background(), tName); !ok {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Error("evicted tunnel route must be removed")
}

func TestConnectPerIPRateLimited(t *testing.T) {
	h := newHarness(t, 0, func(c *config.ServeCmd) { c.LimitRPM = 2 })
	var last int
	for i := 0; i < 3; i++ {
		resp, err := http.Get(h.srv.URL + "/connect")
		if err != nil {
			t.Fatal(err)
		}
		last = resp.StatusCode
		_ = resp.Body.Close()
	}
	if last != http.StatusTooManyRequests {
		t.Errorf("3rd attempt status = %d, want 429", last)
	}
	if h.rec.Count("reject", "rate_connect") < 1 {
		t.Error("rate_connect not recorded")
	}
}

func TestConnectPreAuthSemaphoreFull(t *testing.T) {
	h := newHarness(t, 0, func(c *config.ServeCmd) {
		c.LimitConnectPending = 1
		c.ConnectAuthTimeout = 5 * time.Second
	})
	// First raw dial holds the only slot (server is blocked in authenticate awaiting AUTH).
	ws1, _ := rawDial(t, h.wsURL(), h.host(tName))
	defer func() { _ = ws1.Close(1000, "") }()
	// Second attempt must be refused 503 before upgrade.
	resp, err := http.Get(h.srv.URL + "/connect")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusServiceUnavailable {
		t.Errorf("status = %d, want 503", resp.StatusCode)
	}
	if h.rec.Count("reject", "connect_pending") < 1 {
		t.Error("connect_pending not recorded")
	}
}

func TestConcurrentRequestsDemuxByReqid(t *testing.T) {
	h := newHarness(t, 0, nil)
	phone := h.connectPhone(tName, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, r.URL.Path)
	}))
	defer func() { _ = phone.Close() }()

	var wg sync.WaitGroup
	errs := make(chan string, 4)
	for i := 0; i < 4; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			path := fmt.Sprintf("/p%d", i)
			resp := h.mgr.RouteLocal(context.Background(), routeReq(tName, fmt.Sprintf("r%d", i), "GET", path, nil))
			if resp == nil || string(resp.Body) != path {
				errs <- fmt.Sprintf("req %d got %v", i, resp)
			}
		}(i)
	}
	wg.Wait()
	close(errs)
	for e := range errs {
		t.Error(e)
	}
}

func TestErrorFrameResolvesAs502(t *testing.T) {
	h := newHarness(t, 0, nil)
	p := h.rawPhoneConnect(tName)
	respCh := make(chan *wire.RespEnvelope, 1)
	go func() {
		respCh <- h.mgr.RouteLocal(context.Background(), routeReq(tName, "r1", "GET", "/mcp", nil))
	}()
	reqid, _ := p.drainRequest()
	p.write(wire.ERROR, wire.EncodeErrorHeader(reqid, "backend boom"), nil)
	resp := <-respCh
	if resp.Status != http.StatusBadGateway || resp.ErrCode != "phone_error" || resp.Err != "backend boom" {
		t.Errorf("ERROR frame resolution = %+v", resp)
	}
}

func TestNodeDeadlineReleasesPending(t *testing.T) {
	h := newHarness(t, 0, nil)
	p := h.rawPhoneConnect(tName)
	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()
	respCh := make(chan *wire.RespEnvelope, 1)
	go func() { respCh <- h.mgr.RouteLocal(ctx, routeReq(tName, "r1", "GET", "/mcp", nil)) }()
	_, _ = p.drainRequest() // read but never respond
	resp := <-respCh
	if resp != nil {
		t.Errorf("deadline should release with nil resp, got %+v", resp)
	}
}

func TestChunkedResponseReassembles(t *testing.T) {
	h := newHarness(t, 0, nil)
	big := bytes.Repeat([]byte("A"), wire.ChunkSize*2+123)
	phone := h.connectPhone(tName, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write(big)
	}))
	defer func() { _ = phone.Close() }()
	resp := h.mgr.RouteLocal(context.Background(), routeReq(tName, "r1", "GET", "/mcp", nil))
	if !bytes.Equal(resp.Body, big) {
		t.Errorf("chunked response not reassembled byte-exact (%d vs %d)", len(resp.Body), len(big))
	}
}

func TestRequestChunksReassembleOnPhone(t *testing.T) {
	h := newHarness(t, 0, nil)
	phone := h.connectPhone(tName, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		_, _ = w.Write(body) // echo
	}))
	defer func() { _ = phone.Close() }()
	big := bytes.Repeat([]byte("B"), wire.ChunkSize*2+7)
	resp := h.mgr.RouteLocal(context.Background(), routeReq(tName, "r1", "POST", "/mcp", big))
	if !bytes.Equal(resp.Body, big) {
		t.Errorf("multi-chunk request not reassembled on phone")
	}
	// Empty-body request (zero chunks) dispatches with an empty body.
	respE := h.mgr.RouteLocal(context.Background(), routeReq(tName, "r2", "POST", "/mcp", nil))
	if respE.Status != 200 || len(respE.Body) != 0 {
		t.Errorf("empty-body request = %+v", respE)
	}
}

func TestZeroLengthChunkTolerated(t *testing.T) {
	h := newHarness(t, 0, nil)
	p := h.rawPhoneConnect(tName)
	respCh := make(chan *wire.RespEnvelope, 1)
	go func() { respCh <- h.mgr.RouteLocal(context.Background(), routeReq(tName, "r1", "GET", "/mcp", nil)) }()
	reqid, _ := p.drainRequest()
	p.write(wire.RESPONSE_HEAD, wire.EncodeRespHeader(reqid, 204, nil), nil)
	p.write(wire.RESPONSE_BODY_CHUNK, wire.EncodeReqIDHeader(reqid), []byte{}) // zero-length chunk
	p.write(wire.RESPONSE_END, wire.EncodeReqIDHeader(reqid), nil)
	resp := <-respCh
	if resp.Status != 204 || len(resp.Body) != 0 {
		t.Errorf("zero-length chunk resp = %+v", resp)
	}
}

func TestPhoneMalformedStatusClampedTo502(t *testing.T) {
	// A phone is authenticated but its response CONTENT is untrusted; an out-of-range status
	// (0 from an omitted field, or > 599) must be clamped, not passed through (it would panic the
	// frontend's http.WriteHeader).
	for _, bad := range []int{0, 1000, 42} {
		h := newHarness(t, 0, nil)
		p := h.rawPhoneConnect(tName)
		respCh := make(chan *wire.RespEnvelope, 1)
		go func() { respCh <- h.mgr.RouteLocal(context.Background(), routeReq(tName, "r1", "GET", "/mcp", nil)) }()
		reqid, _ := p.drainRequest()
		p.write(wire.RESPONSE_HEAD, wire.EncodeRespHeader(reqid, bad, nil), nil)
		p.write(wire.RESPONSE_END, wire.EncodeReqIDHeader(reqid), nil)
		resp := <-respCh
		if resp.Status != http.StatusBadGateway {
			t.Errorf("phone status %d must clamp to 502, got %d", bad, resp.Status)
		}
	}
}

func TestResponseOverCapAborts(t *testing.T) {
	h := newHarness(t, 0, func(c *config.ServeCmd) { c.LimitResponse = "64kb" })
	p := h.rawPhoneConnect(tName)
	respCh := make(chan *wire.RespEnvelope, 1)
	go func() { respCh <- h.mgr.RouteLocal(context.Background(), routeReq(tName, "r1", "GET", "/mcp", nil)) }()
	reqid, _ := p.drainRequest()
	p.write(wire.RESPONSE_HEAD, wire.EncodeRespHeader(reqid, 200, nil), nil)
	chunk := bytes.Repeat([]byte("X"), wire.ChunkSize)
	for i := 0; i < 3; i++ { // 3 x 32KiB = 96KiB > 64KiB cap
		p.write(wire.RESPONSE_BODY_CHUNK, wire.EncodeReqIDHeader(reqid), chunk)
	}
	resp := <-respCh
	if resp.ErrCode != "response_too_large" || resp.Status != http.StatusBadGateway {
		t.Errorf("over-cap resp = %+v", resp)
	}
	if h.rec.Count("reject", "response_too_large") != 1 {
		t.Error("response_too_large not recorded once")
	}
}

func TestWSDropFailsPendingAndUnbinds(t *testing.T) {
	h := newHarness(t, 0, nil)
	phone := h.connectPhone(tName, okHandler("ok"))
	_ = phone.Close()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if _, _, ok, _ := h.reg.Lookup(context.Background(), tName); !ok {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Error("route must be removed after WS drop")
}

func TestSameNodeReconnectNotClobbered(t *testing.T) {
	h := newHarness(t, 0, nil)
	cert, key := h.issue(tName)
	// phone1
	p1, err := tunneltest.Dial(context.Background(), h.wsURL(), h.host(tName), cert, key, okHandler("v1"))
	if err != nil {
		t.Fatal(err)
	}
	h.waitBound(tName)
	// phone2: SAME cert/key (same fingerprint) → same-node rebind onto a new connID
	p2, err := tunneltest.Dial(context.Background(), h.wsURL(), h.host(tName), cert, key, okHandler("v2"))
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = p2.Close() }()
	// Give the server a moment to overwrite conns[name] with conn2.
	time.Sleep(100 * time.Millisecond)
	_ = p1.Close() // stale conn teardown must NOT clobber conn2
	time.Sleep(100 * time.Millisecond)
	if _, _, ok, _ := h.reg.Lookup(context.Background(), tName); !ok {
		t.Fatal("route must survive the stale conn teardown")
	}
	resp := h.mgr.RouteLocal(context.Background(), routeReq(tName, "r1", "GET", "/mcp", nil))
	if resp == nil || resp.Status != 200 {
		t.Errorf("requests must keep flowing to conn2: %+v", resp)
	}
}

func TestHeartbeatNotOwnerClosesStale(t *testing.T) {
	h := newHarness(t, 0, nil)
	phone := h.connectPhone(tName, okHandler("ok"))
	defer func() { _ = phone.Close() }()
	v, _ := h.mgr.conns.Load(tName)
	c := v.(*Conn)
	// Simulate the phone re-binding elsewhere.
	if err := h.reg.Bind(context.Background(), tName, "nodeB", c.fp, "otherconn"); err != nil {
		t.Fatal(err)
	}
	if stop := c.heartbeatOnce(); !stop {
		t.Error("not-owner heartbeat must stop the loop")
	}
	waitRec(t, h.rec, "wsdisconnect", "superseded", 1)
	node, _, ok, _ := h.reg.Lookup(context.Background(), tName)
	if !ok || node != "nodeB" {
		t.Errorf("stale teardown must leave the new node's route: node=%q ok=%v", node, ok)
	}
}

func TestLapsedRouteReboundByHeartbeat(t *testing.T) {
	h := newHarness(t, 0, nil)
	phone := h.connectPhone(tName, okHandler("ok"))
	defer func() { _ = phone.Close() }()
	v, _ := h.mgr.conns.Load(tName)
	c := v.(*Conn)
	h.mr.Del("route:" + tName) // TTL lapsed while the WS stayed healthy
	if stop := c.heartbeatOnce(); stop {
		t.Error("missing route must self-heal, not stop")
	}
	node, _, ok, _ := h.reg.Lookup(context.Background(), tName)
	if !ok || node != "nodeA" {
		t.Errorf("heartbeat must re-bind the lapsed route: node=%q ok=%v", node, ok)
	}
}

func TestCoLocatedRequestNotDoublePaced(t *testing.T) {
	// Tiny bandwidth (burst = ChunkSize): a co-located request (PacedByNode == this node) must skip
	// the up-bucket drain, so a multi-chunk body completes fast instead of pacing ~seconds.
	h := newHarness(t, wire.ChunkSize, nil)
	phone := h.connectPhone(tName, okHandler("ok"))
	defer func() { _ = phone.Close() }()
	body := bytes.Repeat([]byte("Z"), wire.ChunkSize*5) // 5 chunks; if paced would take ~5s
	req := routeReq(tName, "r1", "POST", "/mcp", body)
	req.PacedByNode = "nodeA" // this node already paced the ingress read
	start := time.Now()
	resp := h.mgr.RouteLocal(context.Background(), req)
	if resp.Status != 200 {
		t.Fatalf("status %d", resp.Status)
	}
	if elapsed := time.Since(start); elapsed > 2*time.Second {
		t.Errorf("co-located request was double-paced (took %s)", elapsed)
	}
}
