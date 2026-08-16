package ca

import (
	"crypto/rand"
	"encoding/base32"
	"errors"
	"regexp"
	"strings"
)

// reserved host labels the name generator must never produce (kept generic because prefix/length
// are configurable): the enroll host, the reserved /connect path source, and the ops labels.
var reserved = map[string]struct{}{
	"enroll":       {},
	"connect":      {},
	"tunnel":       {},
	"grafana":      {},
	"prometheus":   {},
	"alertmanager": {},
	"ntfy":         {},
	"www":          {},
	"api":          {},
	"admin":        {},
}

var labelRe = regexp.MustCompile(`^[a-z0-9-]{1,63}$`)

var noPadBase32 = base32.StdEncoding.WithPadding(base32.NoPadding)

// GenerateName returns prefix + base32(crypto/rand)[:length], lowercase [a-z2-7], skipping the
// reserved-hostname set and any label failing the host-label regex.
func GenerateName(prefix string, length int) (string, error) {
	nbytes := (length*5 + 7) / 8
	for attempt := 0; attempt < 8; attempt++ {
		buf := make([]byte, nbytes)
		if _, err := rand.Read(buf); err != nil {
			return "", err
		}
		enc := strings.ToLower(noPadBase32.EncodeToString(buf))
		if len(enc) < length {
			continue
		}
		name := prefix + enc[:length]
		if _, bad := reserved[name]; bad {
			continue
		}
		if !labelRe.MatchString(name) {
			continue
		}
		return name, nil
	}
	return "", errors.New("ca: could not generate a valid non-reserved name")
}
