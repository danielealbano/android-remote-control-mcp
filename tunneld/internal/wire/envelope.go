// Package wire holds the shared request/response envelopes carried over Redis pub/sub (US5) and the
// binary WebSocket frame codec (US6). Bodies are appended raw (length-prefixed) — never base64,
// which would add ~33% under the bandwidth cap.
package wire

import (
	"encoding/binary"
	"encoding/json"
	"errors"
	"net/http"
)

// ReqEnvelope is a public request bridged from a frontend to the WS-holding node.
//
// AUTHORITY: ClientIP/ForwardedProto/PacedByNode are node/frontend-side metadata (logging,
// diagnostics, double-pacing guard) ONLY — the phone-side adapter reconstructs the http.Request
// EXCLUSIVELY from Method/Path/RawQuery/Host/Header/Body; the app-visible X-Forwarded-* values live
// in Header (put there by ingress.Sanitize, US7.2) and are the single source of truth.
type ReqEnvelope struct {
	ReqID          string      `json:"reqid"`
	Node           string      `json:"node"`
	TunnelName     string      `json:"tunnel_name"`
	Method         string      `json:"method"`
	Path           string      `json:"path"`
	RawQuery       string      `json:"rawquery"`
	Host           string      `json:"host"`
	Header         http.Header `json:"header"`
	Body           []byte      `json:"-"` // appended raw after the JSON header (no base64)
	ClientIP       string      `json:"client_ip"`
	ForwardedProto string      `json:"forwarded_proto"`
	PacedByNode    string      `json:"paced_by_node"` // nodeID whose up-bucket already paced this body (US7 step 8)
}

// RespEnvelope is the phone's response (or a synthetic error) bridged back to the frontend.
type RespEnvelope struct {
	ReqID   string      `json:"reqid"`
	Status  int         `json:"status"`
	Header  http.Header `json:"header"`
	Body    []byte      `json:"-"`
	Err     string      `json:"err"`      // human-readable synthetic-error message ("" for a real phone response)
	ErrCode string      `json:"err_code"` // "" (real) | "response_too_large" | "tunnel_gone" | "phone_error"
}

var errShort = errors.New("wire: envelope too short")

// MarshalReq encodes: 4-byte BE header-len + JSON(fields sans Body) + raw Body.
func MarshalReq(e *ReqEnvelope) []byte {
	hdr, _ := json.Marshal(e)
	return frameBytes(hdr, e.Body)
}

// UnmarshalReq decodes a MarshalReq buffer, copying the raw body out.
func UnmarshalReq(data []byte) (*ReqEnvelope, error) {
	hdr, body, err := splitFrame(data)
	if err != nil {
		return nil, err
	}
	var e ReqEnvelope
	if err := json.Unmarshal(hdr, &e); err != nil {
		return nil, err
	}
	e.Body = append([]byte(nil), body...)
	return &e, nil
}

// MarshalResp encodes: 4-byte BE header-len + JSON(fields sans Body) + raw Body.
func MarshalResp(e *RespEnvelope) []byte {
	hdr, _ := json.Marshal(e)
	return frameBytes(hdr, e.Body)
}

// UnmarshalResp decodes a MarshalResp buffer, copying the raw body out.
func UnmarshalResp(data []byte) (*RespEnvelope, error) {
	hdr, body, err := splitFrame(data)
	if err != nil {
		return nil, err
	}
	var e RespEnvelope
	if err := json.Unmarshal(hdr, &e); err != nil {
		return nil, err
	}
	e.Body = append([]byte(nil), body...)
	return &e, nil
}

func frameBytes(hdr, body []byte) []byte {
	out := make([]byte, 4+len(hdr)+len(body))
	binary.BigEndian.PutUint32(out[:4], uint32(len(hdr)))
	copy(out[4:], hdr)
	copy(out[4+len(hdr):], body)
	return out
}

func splitFrame(data []byte) (hdr, body []byte, err error) {
	if len(data) < 4 {
		return nil, nil, errShort
	}
	n := binary.BigEndian.Uint32(data[:4])
	if int64(4)+int64(n) > int64(len(data)) {
		return nil, nil, errShort
	}
	return data[4 : 4+n], data[4+n:], nil
}
