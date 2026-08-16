// Package ban implements the longest-prefix-match ban/geo engine (IP/CIDR/country/tunnel-name/
// tunnel-fingerprint) with hot-reload and DB-IP CSV country expansion. It is pure and network-free.
//
// Country codes in this package are PLACEHOLDERS ONLY (XX, YY) in all code, comments, and tests —
// never real country codes or names.
package ban

// Reason is the machine label recorded for a matched ban; it doubles as the
// tunneld_rejections_total{reason} label via String() at rejection sites.
type Reason string

const (
	ReasonIP                Reason = "banned_ip"
	ReasonCIDR              Reason = "banned_cidr"
	ReasonCountry           Reason = "banned_country"
	ReasonTunnelName        Reason = "banned_tunnel_name"
	ReasonTunnelFingerprint Reason = "banned_tunnel_fingerprint"
)

func (r Reason) String() string { return string(r) }

// Source records where and why a ban entry fired, so metrics and logs know which layer/file matched.
type Source struct {
	File   string
	Line   int
	Reason Reason
	Detail string
}
