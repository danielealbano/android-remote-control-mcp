package config

import (
	"fmt"
	"strconv"
	"strings"
)

// ParseByteSize parses a byte-size string using BINARY units (kb=1024, mb=1024², gb=1024³).
// A bare number (or a "b" suffix) is bytes. Examples: "1mb"=1048576, "10mb"=10485760,
// "16kb"=16384, "8kb"=8192. It rejects empty input, unknown suffixes, and negative values.
//
// NOTE: byte-size uses BINARY bytes; bitrate (ParseBitrate) uses DECIMAL bits — they are
// deliberately distinct functions and MUST NOT be conflated.
func ParseByteSize(s string) (int64, error) {
	t := strings.TrimSpace(strings.ToLower(s))
	if t == "" {
		return 0, fmt.Errorf("empty byte size")
	}
	var mult int64 = 1
	switch {
	case strings.HasSuffix(t, "gb"):
		mult, t = 1024*1024*1024, strings.TrimSuffix(t, "gb")
	case strings.HasSuffix(t, "mb"):
		mult, t = 1024*1024, strings.TrimSuffix(t, "mb")
	case strings.HasSuffix(t, "kb"):
		mult, t = 1024, strings.TrimSuffix(t, "kb")
	case strings.HasSuffix(t, "b"):
		mult, t = 1, strings.TrimSuffix(t, "b")
	}
	t = strings.TrimSpace(t)
	n, err := strconv.ParseInt(t, 10, 64)
	if err != nil {
		return 0, fmt.Errorf("invalid byte size %q: %w", s, err)
	}
	if n < 0 {
		return 0, fmt.Errorf("negative byte size %q", s)
	}
	return n * mult, nil
}

// ParseBitrate parses a bitrate string using DECIMAL bits (kbit=1e3, mbit=1e6, gbit=1e9 bits/sec)
// and returns BYTES per second (bits / 8). Examples: "1mbit"=125000 bytes/s, "256kbit"=32000
// bytes/s, "300kbit"=37500 bytes/s. A "bit"-family suffix is REQUIRED (a bare number is rejected)
// so the decimal-bits semantics are never ambiguous with ParseByteSize's binary bytes.
func ParseBitrate(s string) (int64, error) {
	t := strings.TrimSpace(strings.ToLower(s))
	if t == "" {
		return 0, fmt.Errorf("empty bitrate")
	}
	var bitsMult int64
	switch {
	case strings.HasSuffix(t, "gbit"):
		bitsMult, t = 1_000_000_000, strings.TrimSuffix(t, "gbit")
	case strings.HasSuffix(t, "mbit"):
		bitsMult, t = 1_000_000, strings.TrimSuffix(t, "mbit")
	case strings.HasSuffix(t, "kbit"):
		bitsMult, t = 1_000, strings.TrimSuffix(t, "kbit")
	case strings.HasSuffix(t, "bit"):
		bitsMult, t = 1, strings.TrimSuffix(t, "bit")
	default:
		return 0, fmt.Errorf("invalid bitrate %q: missing bit/kbit/mbit/gbit suffix", s)
	}
	t = strings.TrimSpace(t)
	n, err := strconv.ParseInt(t, 10, 64)
	if err != nil {
		return 0, fmt.Errorf("invalid bitrate %q: %w", s, err)
	}
	if n < 0 {
		return 0, fmt.Errorf("negative bitrate %q", s)
	}
	return (n * bitsMult) / 8, nil
}
