// Platform-independent shim logic: the wire protocol (identical to the POSIX
// python shim and the agent PtyBridge), endpoint discovery, and command-line
// construction. Kept untagged so it is unit-testable on any host, even though
// the ConPTY bridge itself only builds on Windows.
package main

import (
	"encoding/binary"
	"encoding/json"
	"net"
	"os"
	"path/filepath"
	"strings"
)

const maxFrame = 4 * 1024 * 1024

func readEndpoint() (host string, port int, token string, ok bool) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", 0, "", false
	}
	data, err := os.ReadFile(filepath.Join(home, ".claudedriver", "pty-endpoint"))
	if err != nil {
		return "", 0, "", false
	}
	var ep struct {
		Port  int    `json:"port"`
		Token string `json:"token"`
	}
	if json.Unmarshal(data, &ep) != nil || ep.Port == 0 {
		return "", 0, "", false
	}
	return "127.0.0.1", ep.Port, ep.Token, true
}

func sendFrame(conn net.Conn, kind byte, payload []byte) {
	head := make([]byte, 5)
	head[0] = kind
	binary.BigEndian.PutUint32(head[1:], uint32(len(payload)))
	conn.Write(head)
	if len(payload) > 0 {
		conn.Write(payload)
	}
}

func readFrames(conn net.Conn, handle func(byte, []byte)) {
	head := make([]byte, 5)
	for {
		if _, err := readFull(conn, head); err != nil {
			return
		}
		n := binary.BigEndian.Uint32(head[1:])
		if n > maxFrame {
			return
		}
		payload := make([]byte, n)
		if n > 0 {
			if _, err := readFull(conn, payload); err != nil {
				return
			}
		}
		handle(head[0], payload)
	}
}

func readFull(conn net.Conn, buf []byte) (int, error) {
	total := 0
	for total < len(buf) {
		n, err := conn.Read(buf[total:])
		total += n
		if err != nil {
			return total, err
		}
	}
	return total, nil
}

func buildCommandLine(exe string, args []string) string {
	parts := []string{quoteArg(exe)}
	for _, a := range args {
		parts = append(parts, quoteArg(a))
	}
	return strings.Join(parts, " ")
}

func quoteArg(s string) string {
	if s != "" && !strings.ContainsAny(s, " \t\"") {
		return s
	}
	return `"` + strings.ReplaceAll(s, `"`, `\"`) + `"`
}

func cwd() string {
	d, err := os.Getwd()
	if err != nil {
		return ""
	}
	return d
}

func mustJSON(v any) []byte {
	b, _ := json.Marshal(v)
	return b
}
