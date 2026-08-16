package wire

import (
	"bytes"
	"net/http"
	"os"
	"testing"
)

func ctHeader() http.Header { return http.Header{"Content-Type": {"application/json"}} }

func TestFrameEncodeDecodeRoundTrip(t *testing.T) {
	big := bytes.Repeat([]byte{0xab}, ChunkSize)
	cases := []struct {
		name         string
		typ          FrameType
		header, body []byte
	}{
		{"challenge no body", CHALLENGE, []byte(`{"nonce":"abc"}`), nil},
		{"auth no body", AUTH, []byte(`{"cert":"x","signature":"y"}`), nil},
		{"request head", REQUEST_HEAD, EncodeReqHeader(&ReqEnvelope{ReqID: "r1", Method: "POST", Path: "/mcp", Host: "h"}), nil},
		{"request body chunk 32k", REQUEST_BODY_CHUNK, EncodeReqIDHeader("r1"), big},
		{"request end", REQUEST_END, EncodeReqIDHeader("r1"), nil},
		{"response head", RESPONSE_HEAD, EncodeRespHeader("r1", 200, nil), nil},
		{"response body chunk empty", RESPONSE_BODY_CHUNK, EncodeReqIDHeader("r1"), []byte{}},
		{"response end", RESPONSE_END, EncodeReqIDHeader("r1"), nil},
		{"error", ERROR, EncodeErrorHeader("r1", "boom"), nil},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			enc := EncodeFrame(tc.typ, tc.header, tc.body)
			typ, hdr, body, err := DecodeFrame(enc)
			if err != nil {
				t.Fatal(err)
			}
			if typ != tc.typ {
				t.Errorf("type = %d, want %d", typ, tc.typ)
			}
			if !bytes.Equal(hdr, tc.header) {
				t.Errorf("header mismatch")
			}
			if !bytes.Equal(body, tc.body) && !(len(body) == 0 && len(tc.body) == 0) {
				t.Errorf("body mismatch: %d vs %d", len(body), len(tc.body))
			}
		})
	}
	if _, _, _, err := DecodeFrame([]byte{0x01, 0x00}); err == nil {
		t.Error("short frame must error")
	}
}

// TestGoldenFrameFixtures pins canonical frame encodings against committed golden files so the Go
// and future Kotlin clients cannot drift.
func TestGoldenFrameFixtures(t *testing.T) {
	cases := map[string][]byte{
		"request_head.frame": EncodeFrame(REQUEST_HEAD, EncodeReqHeader(&ReqEnvelope{
			ReqID: "req-0001", Method: "POST", Path: "/mcp", RawQuery: "", Host: "abc2345678.example.test",
			Header: ctHeader(),
		}), nil),
		"request_body_chunk.frame": EncodeFrame(REQUEST_BODY_CHUNK, EncodeReqIDHeader("req-0001"), []byte("hello-body")),
		"request_end.frame":        EncodeFrame(REQUEST_END, EncodeReqIDHeader("req-0001"), nil),
		"response_head.frame":      EncodeFrame(RESPONSE_HEAD, EncodeRespHeader("req-0001", 200, ctHeader()), nil),
		"response_end.frame":       EncodeFrame(RESPONSE_END, EncodeReqIDHeader("req-0001"), nil),
		"error.frame":              EncodeFrame(ERROR, EncodeErrorHeader("req-0001", "backend error"), nil),
	}
	for name, got := range cases {
		want, err := os.ReadFile("testdata/" + name)
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		if !bytes.Equal(got, want) {
			t.Errorf("%s golden drift: got %d bytes, want %d", name, len(got), len(want))
		}
	}
}

func TestFrameReqIDExtraction(t *testing.T) {
	if id := FrameReqID(EncodeReqIDHeader("xyz")); id != "xyz" {
		t.Errorf("FrameReqID = %q, want xyz", id)
	}
	if id := FrameReqID(EncodeErrorHeader("eid", "msg")); id != "eid" {
		t.Errorf("FrameReqID(error) = %q, want eid", id)
	}
}
