package main

import (
	"context"
	"fmt"
	"os/signal"
	"syscall"

	"github.com/alecthomas/kong"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/config"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/logging"
	"github.com/danielealbano/android-remote-control-mcp/tunneld/internal/server"
)

var version = "dev" // overridden via -ldflags at build time

// runServe is a seam so cmd/tunneld tests can assert CLI dispatch without a real server.
var runServe = server.Run

// CLI is the top-level kong command surface.
type CLI struct {
	Serve   config.ServeCmd `cmd:"" help:"Run the tunnel server."`
	Version struct{}        `cmd:"" help:"Print version and exit."`
}

func main() {
	var cli CLI
	kctx := kong.Parse(&cli,
		kong.Name("tunneld"),
		kong.DefaultEnvars("TUNNELD"),
		kong.UsageOnError(),
	)
	switch kctx.Command() {
	case "version":
		fmt.Println("tunneld", version)
		return
	case "serve":
		logger, closeLogs, err := logging.New(cli.Serve.Log)
		kctx.FatalIfErrorf(err)
		defer func() { _ = closeLogs() }()
		ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
		defer stop()
		kctx.FatalIfErrorf(runServe(ctx, cli.Serve, logger, version))
	}
}
