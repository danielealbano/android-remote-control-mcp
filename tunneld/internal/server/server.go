// Package server assembles and runs the tunneld process. The real assembly lives in US10; US1
// provides this stub so cmd/tunneld and config compile against a stable seam with no forward
// dependency on the later stories.
package server

import (
	"context"
	"errors"
	"log/slog"

	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/config"
)

// Run is replaced by the real assembly in US10 (Task 10.1).
func Run(ctx context.Context, cfg config.ServeCmd, logger *slog.Logger, version string) error {
	_ = ctx
	_ = cfg
	_ = logger
	_ = version
	return errors.New("server.Run not yet implemented")
}
