package ban

import (
	"net/netip"
	"testing"
)

// dbipFixture uses ONLY placeholder country codes (XX, YY, ZZ).
const dbipFixture = "1.0.0.0,1.0.0.255,XX\n" +
	"2.0.0.0,2.0.255.255,YY\n" +
	"3.0.0.0,3.0.0.255,ZZ\n"

func TestExpandCountriesDirect(t *testing.T) {
	dir := t.TempDir()
	csv := writeBan(t, dir, "dbip.csv", dbipFixture)
	prefixes, err := ExpandCountries(csv, map[string]struct{}{"XX": {}})
	if err != nil {
		t.Fatal(err)
	}
	if len(prefixes) == 0 {
		t.Fatal("expected prefixes for XX")
	}
	target := netip.MustParseAddr("1.0.0.5")
	found := false
	for _, p := range prefixes {
		if p.Contains(target) {
			found = true
		}
	}
	if !found {
		t.Error("XX range must cover 1.0.0.5")
	}
	// Empty csvPath → error (caller warns-and-skips country entries).
	if _, err := ExpandCountries("", map[string]struct{}{"XX": {}}); err == nil {
		t.Error("empty csvPath must error")
	}
}

func TestCountryExpandsAndMatches(t *testing.T) {
	dir := t.TempDir()
	csv := writeBan(t, dir, "dbip.csv", dbipFixture)
	f := writeBan(t, dir, "bans.txt", "country XX\n")
	e := NewEngine()
	if err := e.Load([]string{f}, csv, discardLog()); err != nil {
		t.Fatal(err)
	}
	src, ok := e.Match(mustAddr("1.0.0.5"))
	if !ok {
		t.Fatal("address in XX range must match")
	}
	if src.Reason != ReasonCountry {
		t.Errorf("reason = %q, want banned_country", src.Reason)
	}
	if _, ok := e.Match(mustAddr("9.9.9.9")); ok {
		t.Error("address outside any banned country must not match")
	}
}

func TestMissingCSVSkipsCountryOnly(t *testing.T) {
	dir := t.TempDir()
	f := writeBan(t, dir, "bans.txt", "country XX\nip 5.5.5.5\n")
	e := NewEngine()
	// No CSV configured → country entries skipped, ip still enforced.
	if err := e.Load([]string{f}, "", discardLog()); err != nil {
		t.Fatal(err)
	}
	if _, ok := e.Match(mustAddr("5.5.5.5")); !ok {
		t.Error("ip entry must still be enforced when the CSV is absent")
	}
	if _, ok := e.Match(mustAddr("1.0.0.5")); ok {
		t.Error("country entries must be skipped when the CSV is absent")
	}
}
