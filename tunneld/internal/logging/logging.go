// Package logging builds a *slog.Logger whose handler fans each record out to one sink per --log
// spec. The spec grammar (semicolon-separated key=value; repeatable via multiple --log flags, which
// kong also comma-splits) is:
//
//	output=std | output=/path/to/file
//	level=debug|info|warn|error         (default info)
//	format=text|json                    (default: text for std, json for files)
//	maxsize=50m maxfiles=20             (file sinks only; lumberjack)
//
// A std sink is an EXACT severity split: a record with level < warn goes ONLY to stdout, a record
// with level >= warn goes ONLY to stderr (never both). A file sink is a single lumberjack-backed
// min-level writer.
package logging

import (
	"fmt"
	"io"
	"log/slog"
	"os"
	"strconv"
	"strings"

	lumberjack "gopkg.in/natefinch/lumberjack.v2"
)

// New builds the fan-out logger writing std sinks to os.Stdout/os.Stderr. closeAll closes every
// file (lumberjack) writer; callers MUST defer it.
func New(specs []string) (logger *slog.Logger, closeAll func() error, err error) {
	return newLogger(specs, os.Stdout, os.Stderr)
}

// newLogger is the injectable core (tests pass in-memory buffers for the std sinks).
func newLogger(specs []string, stdout, stderr io.Writer) (*slog.Logger, func() error, error) {
	parsed, err := parseAll(specs)
	if err != nil {
		return nil, noopClose, err
	}
	var (
		children []slog.Handler
		closers  []io.Closer
	)
	for _, s := range parsed {
		if s.stdout {
			children = append(children, newStdSplit(stdout, stderr, s))
			continue
		}
		lj := &lumberjack.Logger{
			Filename:   s.path,
			MaxSize:    s.maxSizeMB,
			MaxBackups: s.maxFiles,
			Compress:   false,
		}
		closers = append(closers, lj)
		children = append(children, newLeaf(lj, s))
	}
	closeAll := func() error {
		var firstErr error
		for _, c := range closers {
			if e := c.Close(); e != nil && firstErr == nil {
				firstErr = e
			}
		}
		return firstErr
	}
	return slog.New(&fanoutHandler{children: children}), closeAll, nil
}

func noopClose() error { return nil }

// spec is one parsed --log sink.
type spec struct {
	stdout    bool
	path      string
	level     slog.Level
	json      bool
	maxSizeMB int
	maxFiles  int
}

// ParseSpecs validates the spec grammar WITHOUT side effects (no handlers built, no files opened).
// config.Validate() calls this for the "--log spec parses" check.
func ParseSpecs(specs []string) error {
	_, err := parseAll(specs)
	return err
}

func parseAll(specs []string) ([]spec, error) {
	if len(specs) == 0 {
		specs = []string{"output=std;level=info"}
	}
	out := make([]spec, 0, len(specs))
	for _, raw := range specs {
		s, err := parseSpec(raw)
		if err != nil {
			return nil, err
		}
		out = append(out, s)
	}
	return out, nil
}

func parseSpec(raw string) (spec, error) {
	s := spec{level: slog.LevelInfo, maxSizeMB: 50, maxFiles: 20}
	fields := strings.Split(raw, ";")
	var (
		haveOutput bool
		haveFormat bool
		formatJSON bool
	)
	for _, f := range fields {
		f = strings.TrimSpace(f)
		if f == "" {
			continue
		}
		k, v, ok := strings.Cut(f, "=")
		if !ok {
			return spec{}, fmt.Errorf("invalid log field %q (want key=value)", f)
		}
		k, v = strings.TrimSpace(strings.ToLower(k)), strings.TrimSpace(v)
		switch k {
		case "output":
			haveOutput = true
			if v == "std" {
				s.stdout = true
			} else {
				if v == "" {
					return spec{}, fmt.Errorf("empty output path")
				}
				s.path = v
			}
		case "level":
			lvl, err := parseLevel(v)
			if err != nil {
				return spec{}, err
			}
			s.level = lvl
		case "format":
			haveFormat = true
			switch strings.ToLower(v) {
			case "text":
				formatJSON = false
			case "json":
				formatJSON = true
			default:
				return spec{}, fmt.Errorf("invalid log format %q (want text|json)", v)
			}
		case "maxsize":
			n, err := parseMaxSizeMB(v)
			if err != nil {
				return spec{}, err
			}
			s.maxSizeMB = n
		case "maxfiles":
			n, err := strconv.Atoi(v)
			if err != nil || n < 0 {
				return spec{}, fmt.Errorf("invalid maxfiles %q", v)
			}
			s.maxFiles = n
		default:
			return spec{}, fmt.Errorf("unknown log field %q", k)
		}
	}
	if !haveOutput {
		return spec{}, fmt.Errorf("log spec %q missing output=", raw)
	}
	// Default format: text for std, json for files (unless explicitly set).
	if haveFormat {
		s.json = formatJSON
	} else {
		s.json = !s.stdout
	}
	return s, nil
}

func parseLevel(v string) (slog.Level, error) {
	switch strings.ToLower(v) {
	case "debug":
		return slog.LevelDebug, nil
	case "info":
		return slog.LevelInfo, nil
	case "warn":
		return slog.LevelWarn, nil
	case "error":
		return slog.LevelError, nil
	default:
		return 0, fmt.Errorf("invalid log level %q (want debug|info|warn|error)", v)
	}
}

func parseMaxSizeMB(v string) (int, error) {
	t := strings.ToLower(strings.TrimSpace(v))
	t = strings.TrimSuffix(t, "mb")
	t = strings.TrimSuffix(t, "m")
	n, err := strconv.Atoi(t)
	if err != nil || n <= 0 {
		return 0, fmt.Errorf("invalid maxsize %q (want e.g. 50m)", v)
	}
	return n, nil
}

func newHandler(w io.Writer, s spec) slog.Handler {
	opts := &slog.HandlerOptions{Level: s.level}
	if s.json {
		return slog.NewJSONHandler(w, opts)
	}
	return slog.NewTextHandler(w, opts)
}
