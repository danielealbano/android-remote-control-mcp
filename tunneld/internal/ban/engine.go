package ban

import (
	"errors"
	"io/fs"
	"log/slog"
	"maps"
	"net/netip"
	"sync/atomic"

	"github.com/gaissmai/bart"
)

// snapshot is an immutable view swapped in atomically on each successful Load. Readers hold the old
// pointer unaffected during a reload (lock-free hot path).
type snapshot struct {
	table *bart.Table[Source]
	names map[string]Source
	fps   map[string]Source
}

func emptySnapshot() *snapshot {
	return &snapshot{table: &bart.Table[Source]{}, names: map[string]Source{}, fps: map[string]Source{}}
}

// Engine is the hot-reloadable ban table. The zero-value is NOT usable; construct via NewEngine.
type Engine struct {
	current atomic.Pointer[snapshot]
}

// NewEngine returns an engine holding an EMPTY non-nil snapshot, so Match/MatchTunnel before any
// Load return (Source{}, false) — never a nil-pointer panic on the hot path.
func NewEngine() *Engine {
	e := &Engine{}
	e.current.Store(emptySnapshot())
	return e
}

// Match reports whether ip is banned (LPM lookup) and, if so, the matched Source.
func (e *Engine) Match(ip netip.Addr) (Source, bool) {
	return e.current.Load().table.Lookup(ip)
}

// MatchTunnel reports whether the tunnel name or certificate fingerprint is banned.
func (e *Engine) MatchTunnel(name, fingerprint string) (Source, bool) {
	s := e.current.Load()
	if src, ok := s.names[name]; ok {
		return src, true
	}
	if src, ok := s.fps[fingerprint]; ok {
		return src, true
	}
	return Source{}, false
}

// Load parses all files, expands country entries against the DB-IP CSV, builds a fresh table, and
// atomically swaps it in. Absent files (not-exist) and malformed lines are warned-and-skipped; a
// missing/unreadable CSV skips only the country entries. A HARD read error on a configured file
// (e.g. an I/O error or a path that is a directory — NOT not-exist) returns a non-nil error WITHOUT
// swapping, so the previous snapshot is preserved (never emptied on error).
func (e *Engine) Load(files []string, csvPath string, log *slog.Logger) error {
	table := &bart.Table[Source]{}
	names := map[string]Source{}
	fps := map[string]Source{}
	wanted := map[string]struct{}{}

	for _, f := range files {
		p, err := parseFile(f, log)
		if err != nil {
			if errors.Is(err, fs.ErrNotExist) {
				log.Warn("ban file absent, skipping (will load when it appears)", "file", f)
				continue
			}
			log.Warn("ban file read error, keeping previous ban state", "file", f, "err", err)
			return err // do NOT swap — preserve the previous snapshot
		}
		for _, ps := range p.prefixes {
			table.Insert(ps.prefix, ps.source)
		}
		maps.Copy(names, p.names)
		maps.Copy(fps, p.fingerprints)
		for cc := range p.countries {
			wanted[cc] = struct{}{}
		}
	}

	if len(wanted) > 0 {
		prefixes, err := ExpandCountries(csvPath, wanted)
		if err != nil {
			log.Warn("country ban expansion skipped (CSV missing/unreadable); ip/cidr bans still enforced", "csv", csvPath, "err", err)
		} else {
			for _, pfx := range prefixes {
				table.Insert(pfx, Source{Reason: ReasonCountry, File: csvPath, Detail: "country-expansion"})
			}
		}
	}

	e.current.Store(&snapshot{table: table, names: names, fps: fps})
	return nil
}
