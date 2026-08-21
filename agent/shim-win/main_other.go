//go:build !windows

// The Windows ConPTY shim only builds on Windows; on other platforms the POSIX
// python shim (agent/pty/claude-shim.py) is used instead. This stub keeps
// `go build ./...` green on any host.
package main

import (
	"fmt"
	"os"
)

func main() {
	fmt.Fprintln(os.Stderr, "claude-shim-win is Windows-only; use claude-shim.py on this platform")
	os.Exit(1)
}
