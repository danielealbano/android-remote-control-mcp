package ban

import "testing"

func TestParseHandlesAllEntryKinds(t *testing.T) {
	cases := []struct {
		line, kind, value string
	}{
		{"ip 1.2.3.4", "ip", "1.2.3.4"},
		{"cidr 10.0.0.0/8", "cidr", "10.0.0.0/8"},
		{"country XX", "country", "XX"},
		{"tunnel-name abcdef", "tunnel-name", "abcdef"},
		{"tunnel-fingerprint sha256:beef", "tunnel-fingerprint", "sha256:beef"},
		{"  ip  5.5.5.5  # trailing comment", "ip", "5.5.5.5"},
	}
	for _, c := range cases {
		k, v, err := ParseLine(c.line)
		if err != nil {
			t.Errorf("ParseLine(%q) error: %v", c.line, err)
			continue
		}
		if k != c.kind || v != c.value {
			t.Errorf("ParseLine(%q) = (%q,%q), want (%q,%q)", c.line, k, v, c.kind, c.value)
		}
	}
	// Comments and blank lines are skipped (no kind, no error).
	for _, line := range []string{"", "   ", "# full comment"} {
		k, _, err := ParseLine(line)
		if err != nil || k != "" {
			t.Errorf("ParseLine(%q) = kind %q err %v, want skip", line, k, err)
		}
	}
}

func TestParseSkipsMalformedLines(t *testing.T) {
	// Unknown keyword and a value-less line → ParseLine error (caller skips).
	for _, line := range []string{"bogus 1.2.3.4", "ip", "wat"} {
		if _, _, err := ParseLine(line); err == nil {
			t.Errorf("ParseLine(%q) expected error", line)
		}
	}
	// A bad CIDR value passes ParseLine but is skipped by parseFile (invalid netip.Prefix), with the
	// good entry still loaded.
	dir := t.TempDir()
	f := writeBan(t, dir, "bans.txt", "cidr not-a-cidr\nip 7.7.7.7\n")
	p, err := parseFile(f, discardLog())
	if err != nil {
		t.Fatalf("parseFile error: %v", err)
	}
	if len(p.prefixes) != 1 {
		t.Fatalf("expected 1 valid prefix (bad cidr skipped), got %d", len(p.prefixes))
	}
	if p.prefixes[0].source.Reason != ReasonIP {
		t.Errorf("surviving entry reason = %q, want banned_ip", p.prefixes[0].source.Reason)
	}
}
