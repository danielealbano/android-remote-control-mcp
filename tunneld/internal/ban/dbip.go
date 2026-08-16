package ban

import (
	"encoding/csv"
	"errors"
	"io"
	"net/netip"
	"os"
	"strings"

	"go4.org/netipx"
)

// ExpandCountries reads a DB-IP Country Lite CSV (columns: start_ip,end_ip,country_code) and returns
// the covering prefixes for the requested country codes (via netipx.IPRange.Prefixes()). It returns
// (nil, err) if csvPath == "" or the file is unreadable — the caller then warns and skips country
// entries, leaving ip/cidr bans enforced.
//
// The file is tens of MB and parsed only on reload, so it is streamed with ReuseRecord.
func ExpandCountries(csvPath string, wanted map[string]struct{}) ([]netip.Prefix, error) {
	if csvPath == "" {
		return nil, errors.New("no dbip-country-lite-csv configured")
	}
	f, err := os.Open(csvPath) // #nosec G304 -- operator-configured --dbip-country-lite-csv path (deployment trust boundary, not request-derived)
	if err != nil {
		return nil, err
	}
	defer func() { _ = f.Close() }()

	r := csv.NewReader(f)
	r.FieldsPerRecord = 3
	r.ReuseRecord = true

	var out []netip.Prefix
	for {
		rec, err := r.Read()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return nil, err
		}
		cc := strings.ToUpper(strings.TrimSpace(rec[2]))
		if _, ok := wanted[cc]; !ok {
			continue
		}
		start, e1 := netip.ParseAddr(strings.TrimSpace(rec[0]))
		end, e2 := netip.ParseAddr(strings.TrimSpace(rec[1]))
		if e1 != nil || e2 != nil {
			continue // skip a malformed row, keep going
		}
		rng := netipx.IPRangeFrom(start, end)
		if !rng.IsValid() {
			continue
		}
		out = append(out, rng.Prefixes()...)
	}
	return out, nil
}
