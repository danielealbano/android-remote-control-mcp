package wire

import (
	"bytes"
	"net/http"
	"os"
	"path/filepath"
	"testing"
)

func TestEnvelopeRoundTripsWithBinaryBody(t *testing.T) {
	body := []byte{0x00, 0x01, 0xff, 0x00, 'h', 'i', 0x00}
	req := &ReqEnvelope{
		ReqID:          "r1",
		Node:           "nodeA",
		TunnelName:     "abc",
		Method:         "POST",
		Path:           "/mcp",
		RawQuery:       "x=1",
		Host:           "abc.example.test",
		Header:         http.Header{"Content-Type": {"application/json"}, "X-Forwarded-Proto": {"https"}},
		Body:           body,
		ClientIP:       "203.0.113.9",
		ForwardedProto: "https",
		PacedByNode:    "nodeA",
	}
	got, err := UnmarshalReq(MarshalReq(req))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got.Body, body) {
		t.Errorf("body mismatch: %v vs %v", got.Body, body)
	}
	if got.Header.Get("X-Forwarded-Proto") != "https" || got.Method != "POST" || got.Host != "abc.example.test" {
		t.Errorf("header/fields not preserved: %+v", got)
	}
	if got.ReqID != "r1" || got.TunnelName != "abc" || got.PacedByNode != "nodeA" {
		t.Errorf("envelope fields not preserved: %+v", got)
	}

	resp := &RespEnvelope{
		ReqID:   "r1",
		Status:  200,
		Header:  http.Header{"Content-Type": {"text/plain"}},
		Body:    []byte{0x00, 0xde, 0xad, 0x00, 0xbe, 0xef},
		ErrCode: "",
	}
	gotR, err := UnmarshalResp(MarshalResp(resp))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(gotR.Body, resp.Body) {
		t.Errorf("resp body mismatch: %v vs %v", gotR.Body, resp.Body)
	}
	if gotR.Status != 200 || gotR.Header.Get("Content-Type") != "text/plain" {
		t.Errorf("resp fields not preserved: %+v", gotR)
	}

	// Empty body round-trips as zero-length body.
	empty, err := UnmarshalReq(MarshalReq(&ReqEnvelope{ReqID: "e"}))
	if err != nil || len(empty.Body) != 0 {
		t.Errorf("empty body round-trip: body=%v err=%v", empty.Body, err)
	}
}

// TestGoldenEnvelopeFixtures pins the canonical envelope encoding against the committed golden files
// under testdata/ so the Go and future Kotlin clients cannot drift. Regenerate via
// scratchpad genfixtures if the wire format INTENTIONALLY changes.
func TestGoldenEnvelopeFixtures(t *testing.T) {
	req := &ReqEnvelope{
		ReqID:          "req-0001",
		Node:           "node-A",
		TunnelName:     "abc2345678",
		Method:         "POST",
		Path:           "/mcp",
		Host:           "abc2345678.example.test",
		Header:         http.Header{"Content-Type": {"application/json"}, "X-Forwarded-Proto": {"https"}},
		Body:           []byte(`{"jsonrpc":"2.0","method":"ping","id":1}`),
		ClientIP:       "203.0.113.9",
		ForwardedProto: "https",
	}
	want, err := os.ReadFile(filepath.Join("testdata", "req_envelope.bin"))
	if err != nil {
		t.Fatal(err)
	}
	if got := MarshalReq(req); !bytes.Equal(got, want) {
		t.Errorf("req golden drift:\n got %d bytes\nwant %d bytes", len(got), len(want))
	}

	resp := &RespEnvelope{
		ReqID:  "req-0001",
		Status: 200,
		Header: http.Header{"Content-Type": {"application/json"}},
		Body:   []byte(`{"jsonrpc":"2.0","result":"pong","id":1}`),
	}
	wantR, err := os.ReadFile(filepath.Join("testdata", "resp_envelope.bin"))
	if err != nil {
		t.Fatal(err)
	}
	if got := MarshalResp(resp); !bytes.Equal(got, wantR) {
		t.Errorf("resp golden drift:\n got %d bytes\nwant %d bytes", len(got), len(wantR))
	}
}

func TestUnmarshalRejectsShort(t *testing.T) {
	if _, err := UnmarshalReq([]byte{0x00}); err == nil {
		t.Error("short buffer must error")
	}
}
