package logging

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestLogFanoutSplitsBySeverity(t *testing.T) {
	var out, err bytes.Buffer
	logger, closeAll, e := newLogger([]string{"output=std;level=debug"}, &out, &err)
	if e != nil {
		t.Fatal(e)
	}
	defer func() { _ = closeAll() }()

	logger.Info("info-line")
	logger.Warn("warn-line")

	if !strings.Contains(out.String(), "info-line") {
		t.Errorf("info not on stdout: %q", out.String())
	}
	if strings.Contains(out.String(), "warn-line") {
		t.Errorf("warn MUST NOT appear on stdout: %q", out.String())
	}
	if !strings.Contains(err.String(), "warn-line") {
		t.Errorf("warn not on stderr: %q", err.String())
	}
	if strings.Contains(err.String(), "info-line") {
		t.Errorf("info MUST NOT appear on stderr: %q", err.String())
	}
}

func TestLogFanoutWritesFileSink(t *testing.T) {
	path := filepath.Join(t.TempDir(), "tunneld.log")
	logger, closeAll, e := newLogger([]string{"output=" + path + ";level=info;maxsize=50m;maxfiles=20"}, &bytes.Buffer{}, &bytes.Buffer{})
	if e != nil {
		t.Fatal(e)
	}
	logger.Info("file-sink-line", "k", "v")
	if err := closeAll(); err != nil {
		t.Fatalf("closeAll: %v", err)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read log file: %v", err)
	}
	if !strings.Contains(string(data), "file-sink-line") {
		t.Errorf("record not in file sink: %q", string(data))
	}
	// Default file format is JSON.
	if !strings.Contains(string(data), `"msg":"file-sink-line"`) {
		t.Errorf("file sink expected JSON format: %q", string(data))
	}
}

func TestLogDefaultWhenEmpty(t *testing.T) {
	var out, err bytes.Buffer
	logger, closeAll, e := newLogger(nil, &out, &err)
	if e != nil {
		t.Fatal(e)
	}
	defer func() { _ = closeAll() }()
	logger.Debug("debug-line") // below info floor → dropped
	logger.Info("info-line")
	if strings.Contains(out.String(), "debug-line") {
		t.Errorf("default level is info; debug MUST be dropped: %q", out.String())
	}
	if !strings.Contains(out.String(), "info-line") {
		t.Errorf("info not emitted at default level: %q", out.String())
	}
}

func TestParseSpecsRejectsBad(t *testing.T) {
	for _, spec := range []string{
		"level=info",             // missing output
		"output=std;level=bogus", // bad level
		"output=std;format=yaml", // bad format
		"output=/x;maxsize=abc",  // bad maxsize
		"output=std;unknown=1",   // unknown field
		"badfield",               // not key=value
	} {
		if err := ParseSpecs([]string{spec}); err == nil {
			t.Errorf("ParseSpecs(%q) expected error", spec)
		}
	}
	// Valid specs parse.
	if err := ParseSpecs([]string{"output=std;level=info", "output=/tmp/x.log;maxsize=10m;maxfiles=5"}); err != nil {
		t.Errorf("valid specs rejected: %v", err)
	}
}
