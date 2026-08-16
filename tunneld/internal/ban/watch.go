package ban

import (
	"context"
	"log/slog"
	"os"
	"time"
)

// Watch polls the max mtime across all ban files + the CSV every `poll`; on a change it reloads the
// engine and, on a SUCCESSFUL load, invokes onReload(e) (nil-safe). The initial load happens once
// before the poll loop (and fires onReload on success). A load error keeps the previous snapshot
// (never empties the table) and does NOT fire onReload.
//
// onReload is how live name/fingerprint revocation reaches the WS manager (US6 EvictBanned).
func Watch(ctx context.Context, e *Engine, files []string, csvPath string, poll time.Duration, onReload func(*Engine), log *slog.Logger) {
	w := &watcher{e: e, files: files, csv: csvPath, onReload: onReload, log: log}
	w.initial()

	ticker := time.NewTicker(poll)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			w.tick()
		}
	}
}

// watcher holds the poll state; split out so tests can drive initial()/tick() deterministically
// without depending on ticker timing.
type watcher struct {
	e        *Engine
	files    []string
	csv      string
	onReload func(*Engine)
	log      *slog.Logger
	last     time.Time
}

func (w *watcher) initial() {
	w.last = maxMtime(w.files, w.csv)
	if err := w.e.Load(w.files, w.csv, w.log); err != nil {
		w.log.Warn("initial ban load error; engine stays at empty/previous snapshot until next successful load", "err", err)
		return
	}
	if w.onReload != nil {
		w.onReload(w.e)
	}
}

func (w *watcher) tick() {
	cur := maxMtime(w.files, w.csv)
	if !cur.After(w.last) {
		return
	}
	w.last = cur
	if err := w.e.Load(w.files, w.csv, w.log); err != nil {
		w.log.Warn("ban reload error; keeping previous snapshot", "err", err)
		return // do NOT fire onReload
	}
	if w.onReload != nil {
		w.onReload(w.e)
	}
}

// maxMtime returns the latest modification time across the ban files and the CSV. Absent paths are
// ignored (zero time). A path that exists (including a directory) contributes its mtime, so a file
// replaced by a directory is still detected as a change (and the subsequent Load reports the read
// error).
func maxMtime(files []string, csvPath string) time.Time {
	var max time.Time
	paths := files
	if csvPath != "" {
		paths = append(append([]string{}, files...), csvPath)
	}
	for _, p := range paths {
		if p == "" {
			continue
		}
		fi, err := os.Stat(p)
		if err != nil {
			continue
		}
		if mt := fi.ModTime(); mt.After(max) {
			max = mt
		}
	}
	return max
}
