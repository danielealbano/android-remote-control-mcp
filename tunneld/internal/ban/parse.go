package ban

import (
	"fmt"
	"log/slog"
	"net/netip"
	"os"
	"strings"
)

// ParseLine parses one ban-file line into (kind, value). A blank line or a comment (everything from
// the first '#') yields ("", "", nil) — a skip, not an error. An unknown keyword or a line with no
// value yields a non-nil error (the caller warns and skips it).
func ParseLine(line string) (kind, value string, err error) {
	if i := strings.IndexByte(line, '#'); i >= 0 {
		line = line[:i]
	}
	line = strings.TrimSpace(line)
	if line == "" {
		return "", "", nil
	}
	fields := strings.Fields(line)
	if len(fields) < 2 {
		return "", "", fmt.Errorf("malformed ban line %q (want '<kind> <value>')", line)
	}
	kind = strings.ToLower(fields[0])
	value = fields[1]
	switch kind {
	case "ip", "cidr", "country", "tunnel-name", "tunnel-fingerprint":
		return kind, value, nil
	default:
		return "", "", fmt.Errorf("unknown ban kind %q", kind)
	}
}

// parsed holds one file's contribution: prefix entries (ip→/32 or /128, cidr as-is), the set of
// wanted country codes (expanded later against the DB-IP CSV), and name/fingerprint bans.
type parsed struct {
	prefixes     []prefixSource
	countries    map[string]Source // country code → representative source
	names        map[string]Source
	fingerprints map[string]Source
}

type prefixSource struct {
	prefix netip.Prefix
	source Source
}

// parseFile reads and parses a ban file. A read error (including not-exist) is returned to the
// caller, which distinguishes not-exist (skip) from a hard read error (keep previous snapshot).
// Malformed individual lines are warned-and-skipped (never fatal).
func parseFile(path string, log *slog.Logger) (parsed, error) {
	data, err := os.ReadFile(path) // #nosec G304 -- operator-configured --ban-file path (deployment trust boundary, not request-derived)
	if err != nil {
		return parsed{}, err
	}
	p := parsed{
		countries:    map[string]Source{},
		names:        map[string]Source{},
		fingerprints: map[string]Source{},
	}
	for i, raw := range strings.Split(string(data), "\n") {
		lineNo := i + 1
		kind, value, perr := ParseLine(raw)
		if perr != nil {
			log.Warn("skipping malformed ban line", "file", path, "line", lineNo, "err", perr)
			continue
		}
		if kind == "" {
			continue
		}
		src := Source{File: path, Line: lineNo}
		switch kind {
		case "ip":
			addr, e := netip.ParseAddr(value)
			if e != nil {
				log.Warn("skipping invalid ip", "file", path, "line", lineNo, "value", value)
				continue
			}
			src.Reason, src.Detail = ReasonIP, value
			p.prefixes = append(p.prefixes, prefixSource{netip.PrefixFrom(addr, addr.BitLen()), src})
		case "cidr":
			pfx, e := netip.ParsePrefix(value)
			if e != nil {
				log.Warn("skipping invalid cidr", "file", path, "line", lineNo, "value", value)
				continue
			}
			src.Reason, src.Detail = ReasonCIDR, value
			p.prefixes = append(p.prefixes, prefixSource{pfx.Masked(), src})
		case "country":
			cc := strings.ToUpper(value)
			src.Reason, src.Detail = ReasonCountry, cc
			p.countries[cc] = src
		case "tunnel-name":
			src.Reason, src.Detail = ReasonTunnelName, value
			p.names[value] = src
		case "tunnel-fingerprint":
			src.Reason, src.Detail = ReasonTunnelFingerprint, value
			p.fingerprints[value] = src
		}
	}
	return p, nil
}
