package wire

import (
	"bytes"
	"crypto/rand"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
)

// FrameType identifies a WebSocket binary frame. Frame layout: [type:1][headerLen:4 BE][header JSON][body].
type FrameType byte

const (
	CHALLENGE FrameType = iota + 1 // server→phone {nonce}, no body
	AUTH                           // phone→server {cert, signature}, no body
	REQUEST_HEAD                   // {reqid, method, path, rawquery, host, header}, no body
	REQUEST_BODY_CHUNK             // {reqid} + ≤ChunkSize body
	REQUEST_END                    // {reqid}, no body — dispatch trigger
	RESPONSE_HEAD                  // {reqid, status, header}, no body
	RESPONSE_BODY_CHUNK            // {reqid} + ≤ChunkSize body
	RESPONSE_END                   // {reqid}, no body
	ERROR                          // {reqid, message}, no body
)

// ChunkSize is the max body bytes per REQUEST_BODY_CHUNK/RESPONSE_BODY_CHUNK frame (bandwidth pacing
// granularity). It equals config's bandwidth floor (US1) and the WS SetReadLimit base (US6).
const ChunkSize = 32 * 1024

var errShortFrame = errors.New("wire: frame too short")

// EncodeFrame builds [type:1][headerLen:4 BE][header][body].
func EncodeFrame(t FrameType, header, body []byte) []byte {
	out := make([]byte, 1+4+len(header)+len(body))
	out[0] = byte(t)
	binary.BigEndian.PutUint32(out[1:5], uint32(len(header))) // #nosec G115 -- protocol-bounded header length (< 16 KB), never near uint32 max
	copy(out[5:], header)
	copy(out[5+len(header):], body)
	return out
}

// DecodeFrame splits a binary frame into its type, header, and body slices (aliasing the input).
func DecodeFrame(data []byte) (FrameType, []byte, []byte, error) {
	if len(data) < 5 {
		return 0, nil, nil, errShortFrame
	}
	n := binary.BigEndian.Uint32(data[1:5])
	if int64(5)+int64(n) > int64(len(data)) {
		return 0, nil, nil, errShortFrame
	}
	return FrameType(data[0]), data[5 : 5+n], data[5+n:], nil
}

// --- header adapters (all reqid-carrying) ---

type reqHeaderJSON struct {
	ReqID    string      `json:"reqid"`
	Method   string      `json:"method"`
	Path     string      `json:"path"`
	RawQuery string      `json:"rawquery"`
	Host     string      `json:"host"`
	Header   http.Header `json:"header"`
}

type respHeaderJSON struct {
	ReqID  string      `json:"reqid"`
	Status int         `json:"status"`
	Header http.Header `json:"header"`
}

type reqidHeaderJSON struct {
	ReqID string `json:"reqid"`
}

type errorHeaderJSON struct {
	ReqID   string `json:"reqid"`
	Message string `json:"message"`
}

// EncodeReqHeader builds the REQUEST_HEAD header (method/path/headers; NO body — the body follows in
// REQUEST_BODY_CHUNK frames).
func EncodeReqHeader(r *ReqEnvelope) []byte {
	b, _ := json.Marshal(reqHeaderJSON{
		ReqID:    r.ReqID,
		Method:   r.Method,
		Path:     r.Path,
		RawQuery: r.RawQuery,
		Host:     r.Host,
		Header:   r.Header,
	})
	return b
}

// DecodeReqHeader reconstructs the *http.Request from a REQUEST_HEAD header and the ACCUMULATED body
// (called at REQUEST_END). Phone-side (FakePhone, US11 client).
func DecodeReqHeader(header, body []byte) (reqid string, req *http.Request) {
	var h reqHeaderJSON
	_ = json.Unmarshal(header, &h)
	u := &url.URL{Path: h.Path, RawQuery: h.RawQuery}
	req, _ = http.NewRequest(h.Method, u.String(), bytes.NewReader(body))
	if req == nil { // defensive: a bad method yields a nil request
		req, _ = http.NewRequest(http.MethodGet, "/", bytes.NewReader(body))
	}
	req.Host = h.Host
	if h.Header != nil {
		req.Header = h.Header
	}
	req.ContentLength = int64(len(body))
	return h.ReqID, req
}

// EncodeRespHeader builds the RESPONSE_HEAD header.
func EncodeRespHeader(reqid string, code int, h http.Header) []byte {
	b, _ := json.Marshal(respHeaderJSON{ReqID: reqid, Status: code, Header: h})
	return b
}

// DecodeRespHeader parses a RESPONSE_HEAD header.
func DecodeRespHeader(header []byte) (reqid string, code int, h http.Header) {
	var r respHeaderJSON
	_ = json.Unmarshal(header, &r)
	return r.ReqID, r.Status, r.Header
}

// EncodeReqIDHeader builds the {reqid} header for the chunk/END frames.
func EncodeReqIDHeader(reqid string) []byte {
	b, _ := json.Marshal(reqidHeaderJSON{ReqID: reqid})
	return b
}

// EncodeErrorHeader builds the ERROR frame header {reqid, message}.
func EncodeErrorHeader(reqid, message string) []byte {
	b, _ := json.Marshal(errorHeaderJSON{ReqID: reqid, Message: message})
	return b
}

// DecodeErrorHeader parses an ERROR frame header.
func DecodeErrorHeader(header []byte) (reqid, message string) {
	var e errorHeaderJSON
	_ = json.Unmarshal(header, &e)
	return e.ReqID, e.Message
}

// FrameReqID cheaply extracts the reqid from any reqid-carrying frame header (read-pump demux).
func FrameReqID(header []byte) string {
	var h reqidHeaderJSON
	_ = json.Unmarshal(header, &h)
	return h.ReqID
}

// NewReqID returns a fresh random request id (hex of 16 crypto/rand bytes).
func NewReqID() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}
