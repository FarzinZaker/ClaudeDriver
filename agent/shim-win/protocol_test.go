package main

import (
	"encoding/json"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// The frame format must match the agent PtyBridge and the POSIX shim exactly:
// 1 type byte + 4-byte big-endian length + payload.
func TestFrameRoundTrip(t *testing.T) {
	server, client := net.Pipe()
	defer server.Close()
	defer client.Close()

	got := make(chan struct {
		kind byte
		data []byte
	}, 4)
	go readFrames(server, func(kind byte, payload []byte) {
		got <- struct {
			kind byte
			data []byte
		}{kind, append([]byte(nil), payload...)}
	})

	go func() {
		sendFrame(client, 'H', []byte(`{"token":"t","sid":"pty-1"}`))
		sendFrame(client, 'O', []byte("hello world"))
		sendFrame(client, 'X', []byte(`{"code":0}`))
	}()

	want := []struct {
		kind byte
		data string
	}{{'H', `{"token":"t","sid":"pty-1"}`}, {'O', "hello world"}, {'X', `{"code":0}`}}
	for _, w := range want {
		select {
		case g := <-got:
			if g.kind != w.kind || string(g.data) != w.data {
				t.Fatalf("frame mismatch: got %c/%q want %c/%q", g.kind, g.data, w.kind, w.data)
			}
		case <-time.After(2 * time.Second):
			t.Fatalf("timed out waiting for frame %c", w.kind)
		}
	}
}

func TestReadFramesRejectsOversizedLength(t *testing.T) {
	server, client := net.Pipe()
	defer server.Close()
	go func() {
		// A length past the 4 MiB cap must abort parsing (guard against a hostile peer).
		client.Write([]byte{'O', 0xFF, 0xFF, 0xFF, 0xFF})
		client.Close()
	}()
	done := make(chan bool, 1)
	go func() {
		readFrames(server, func(byte, []byte) { t.Error("should not deliver an oversized frame") })
		done <- true
	}()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("readFrames did not bail on an oversized length")
	}
}

func TestReadEndpoint(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("USERPROFILE", home) // Windows home resolution
	if err := os.MkdirAll(filepath.Join(home, ".claudedriver"), 0o755); err != nil {
		t.Fatal(err)
	}
	body, _ := json.Marshal(map[string]any{"port": 54966, "token": "abc"})
	if err := os.WriteFile(filepath.Join(home, ".claudedriver", "pty-endpoint"), body, 0o600); err != nil {
		t.Fatal(err)
	}
	host, port, token, ok := readEndpoint()
	if !ok || host != "127.0.0.1" || port != 54966 || token != "abc" {
		t.Fatalf("readEndpoint = %q/%d/%q ok=%v", host, port, token, ok)
	}
}

func TestReadEndpointMissingIsFailOpen(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("USERPROFILE", home)
	if _, _, _, ok := readEndpoint(); ok {
		t.Fatal("missing endpoint must report not-ok so the shim runs claude natively")
	}
}

func TestQuoteArg(t *testing.T) {
	cases := map[string]string{
		"simple":       "simple",
		"has space":    `"has space"`,
		`quote"inside`: `"quote\"inside"`,
	}
	for in, want := range cases {
		if got := quoteArg(in); got != want {
			t.Errorf("quoteArg(%q) = %q want %q", in, got, want)
		}
	}
	if cl := buildCommandLine(`C:\Program Files\claude.exe`, []string{"--print", "hi there"}); cl != `"C:\Program Files\claude.exe" --print "hi there"` {
		t.Errorf("buildCommandLine = %q", cl)
	}
}
