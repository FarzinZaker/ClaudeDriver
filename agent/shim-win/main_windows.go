//go:build windows

// Transparent `claude.exe` shim for Windows.
//
// Runs the real claude inside a ConPTY (pseudo console) so ClaudeDriver's agent
// can mirror the live output to the dashboard and inject operator keystrokes,
// while the local user drives an ordinary console. Fail-open by construction: no
// console, no agent, no endpoint, or a refused socket -> it runs the real claude
// with inherited handles and adds nothing.
//
// Wire protocol (framed: 1 type byte + 4-byte big-endian length + payload) — the
// SAME protocol the POSIX python shim and the agent PtyBridge speak:
//   shim -> agent : H hello{token,cwd,sid,cols,rows} · O output-bytes · R resize{cols,rows} · X exit{code}
//   agent -> shim : I input-bytes-to-inject
package main

import (
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"syscall"
	"time"
	"unsafe"
)

var (
	kernel32                        = syscall.NewLazyDLL("kernel32.dll")
	procCreatePseudoConsole         = kernel32.NewProc("CreatePseudoConsole")
	procResizePseudoConsole         = kernel32.NewProc("ResizePseudoConsole")
	procClosePseudoConsole          = kernel32.NewProc("ClosePseudoConsole")
	procCreatePipe                  = kernel32.NewProc("CreatePipe")
	procInitializeProcThreadAttrLst = kernel32.NewProc("InitializeProcThreadAttributeList")
	procUpdateProcThreadAttribute   = kernel32.NewProc("UpdateProcThreadAttribute")
	procDeleteProcThreadAttrList    = kernel32.NewProc("DeleteProcThreadAttributeList")
	procCreateProcessW              = kernel32.NewProc("CreateProcessW")
	procGetConsoleMode              = kernel32.NewProc("GetConsoleMode")
	procSetConsoleMode              = kernel32.NewProc("SetConsoleMode")
	procGetConsoleScreenBufferInfo  = kernel32.NewProc("GetConsoleScreenBufferInfo")
	procGetExitCodeProcess          = kernel32.NewProc("GetExitCodeProcess")
)

const (
	stdInputHandle  = ^uintptr(9)  // -10
	stdOutputHandle = ^uintptr(10) // -11

	enableProcessedInput          = 0x0001
	enableLineInput               = 0x0002
	enableEchoInput               = 0x0004
	enableVirtualTerminalInput    = 0x0200
	enableVirtualTerminalProcessg = 0x0004
	disableNewlineAutoReturn      = 0x0008

	extendedStartupInfoPresent   = 0x00080000
	procThreadAttrPseudoConsole  = 0x00020016
	waitObject0                  = 0x0
	infinite                     = 0xFFFFFFFF
)

type coord struct{ X, Y int16 }

type smallRect struct{ Left, Top, Right, Bottom int16 }

type consoleScreenBufferInfo struct {
	Size              coord
	CursorPosition    coord
	Attributes        uint16
	Window            smallRect
	MaximumWindowSize coord
}

type startupInfoEx struct {
	StartupInfo   syscall.StartupInfo
	AttributeList uintptr
}

func main() {
	args := os.Args[1:]

	// Only bridge a genuine interactive console; anything else runs native.
	if !isConsole(getStdHandle(stdInputHandle)) || !isConsole(getStdHandle(stdOutputHandle)) {
		os.Exit(execReal(args))
	}
	host, port, token, ok := readEndpoint()
	if !ok {
		os.Exit(execReal(args))
	}
	conn, err := net.DialTimeout("tcp", fmt.Sprintf("%s:%d", host, port), time.Second)
	if err != nil {
		os.Exit(execReal(args)) // agent down / refusing -> plain claude
	}

	os.Exit(bridge(conn, token, args))
}

// bridge runs the real claude in a ConPTY and shuttles bytes between the user's
// console, the ConPTY, and the agent socket. Any setup failure falls open to a
// native run so `claude` is never broken by the mirror.
func bridge(conn net.Conn, token string, args []string) int {
	real := resolveReal()
	cols, rows := consoleSize()

	inR, inW, err1 := makePipe()
	outR, outW, err2 := makePipe()
	if err1 != nil || err2 != nil {
		return execReal(args)
	}

	var hPC uintptr
	size := uintptr(uint32(uint16(cols)) | uint32(uint16(rows))<<16)
	r, _, _ := procCreatePseudoConsole.Call(size, inR, outW, 0, uintptr(unsafe.Pointer(&hPC)))
	if r != 0 { // S_OK == 0
		return execReal(args)
	}
	// ConPTY owns duplicates of the two handles it was given; close our copies.
	syscall.CloseHandle(syscall.Handle(inR))
	syscall.CloseHandle(syscall.Handle(outW))

	pi, err := spawnInConPTY(real, args, hPC)
	if err != nil {
		procClosePseudoConsole.Call(hPC)
		return execReal(args)
	}

	sid := fmt.Sprintf("pty-%d", pi.ProcessId)
	sendFrame(conn, 'H', mustJSON(map[string]any{
		"token": token, "cwd": cwd(), "sid": sid, "cols": cols, "rows": rows,
	}))

	// Put the console into raw VT mode; restore on exit.
	inH, outH := getStdHandle(stdInputHandle), getStdHandle(stdOutputHandle)
	oldIn, oldOut := getMode(inH), getMode(outH)
	setMode(inH, enableVirtualTerminalInput)
	setMode(outH, oldOut|enableVirtualTerminalProcessg|disableNewlineAutoReturn)
	defer func() { setMode(inH, oldIn); setMode(outH, oldOut) }()

	var once sync.Once
	done := make(chan struct{})
	finish := func() { once.Do(func() { close(done) }) }

	// Console stdin -> ConPTY input.
	go copyFD(uintptr(syscall.Handle(inH)), inW, nil)
	// ConPTY output -> console stdout AND agent socket.
	go func() {
		buf := make([]byte, 4096)
		for {
			n, e := readHandle(outR, buf)
			if n > 0 {
				writeHandle(outH, buf[:n])
				sendFrame(conn, 'O', buf[:n])
			}
			if e != nil || n == 0 {
				finish()
				return
			}
		}
	}()
	// Agent socket -> ConPTY input (inject) — parse frames, honor only 'I'.
	go func() {
		readFrames(conn, func(kind byte, payload []byte) {
			if kind == 'I' {
				writeHandle(inW, payload)
			}
		})
		finish()
	}()
	// Resize watcher: mirror console size changes to the ConPTY.
	go func() {
		lastC, lastR := cols, rows
		t := time.NewTicker(300 * time.Millisecond)
		defer t.Stop()
		for {
			select {
			case <-done:
				return
			case <-t.C:
				c, r := consoleSize()
				if c != lastC || r != lastR {
					lastC, lastR = c, r
					procResizePseudoConsole.Call(hPC, uintptr(uint32(uint16(c))|uint32(uint16(r))<<16))
					sendFrame(conn, 'R', mustJSON(map[string]any{"cols": c, "rows": r}))
				}
			}
		}
	}()

	// Wait for claude to exit.
	syscall.WaitForSingleObject(pi.Process, infinite)
	code := exitCode(uintptr(pi.Process))
	procClosePseudoConsole.Call(hPC) // signals the output goroutine to end
	finish()
	sendFrame(conn, 'X', mustJSON(map[string]any{"code": code}))
	conn.Close()
	return code
}

// ---- process / conpty helpers ------------------------------------------------

func spawnInConPTY(real string, args []string, hPC uintptr) (*syscall.ProcessInformation, error) {
	var siEx startupInfoEx
	siEx.StartupInfo.Cb = uint32(unsafe.Sizeof(siEx))

	var listSize uintptr
	procInitializeProcThreadAttrLst.Call(0, 1, 0, uintptr(unsafe.Pointer(&listSize)))
	attrList := make([]byte, listSize)
	siEx.AttributeList = uintptr(unsafe.Pointer(&attrList[0]))
	r, _, err := procInitializeProcThreadAttrLst.Call(siEx.AttributeList, 1, 0, uintptr(unsafe.Pointer(&listSize)))
	if r == 0 {
		return nil, err
	}
	defer procDeleteProcThreadAttrList.Call(siEx.AttributeList)
	r, _, err = procUpdateProcThreadAttribute.Call(
		siEx.AttributeList, 0, procThreadAttrPseudoConsole, hPC, unsafe.Sizeof(hPC), 0, 0)
	if r == 0 {
		return nil, err
	}

	cmdline := buildCommandLine(real, args)
	argp, _ := syscall.UTF16PtrFromString(cmdline)
	var pi syscall.ProcessInformation
	r, _, err = procCreateProcessW.Call(
		0, uintptr(unsafe.Pointer(argp)), 0, 0, 0,
		extendedStartupInfoPresent, 0, 0,
		uintptr(unsafe.Pointer(&siEx)), uintptr(unsafe.Pointer(&pi)))
	if r == 0 {
		return nil, err
	}
	return &pi, nil
}

func makePipe() (read, write uintptr, err error) {
	var r, w syscall.Handle
	ret, _, e := procCreatePipe.Call(
		uintptr(unsafe.Pointer(&r)), uintptr(unsafe.Pointer(&w)), 0, 0)
	if ret == 0 {
		return 0, 0, e
	}
	return uintptr(r), uintptr(w), nil
}

func exitCode(process uintptr) int {
	var code uint32
	procGetExitCodeProcess.Call(process, uintptr(unsafe.Pointer(&code)))
	return int(code)
}

// ---- console helpers ---------------------------------------------------------

func getStdHandle(which uintptr) uintptr {
	h, _ := syscall.GetStdHandle(int(int32(which)))
	return uintptr(h)
}

func isConsole(h uintptr) bool {
	var mode uint32
	r, _, _ := procGetConsoleMode.Call(h, uintptr(unsafe.Pointer(&mode)))
	return r != 0
}

func getMode(h uintptr) uint32 {
	var mode uint32
	procGetConsoleMode.Call(h, uintptr(unsafe.Pointer(&mode)))
	return mode
}

func setMode(h uintptr, mode uint32) {
	procSetConsoleMode.Call(h, uintptr(mode))
}

func consoleSize() (cols, rows int) {
	var info consoleScreenBufferInfo
	r, _, _ := procGetConsoleScreenBufferInfo.Call(getStdHandle(stdOutputHandle), uintptr(unsafe.Pointer(&info)))
	if r == 0 {
		return 80, 24
	}
	cols = int(info.Window.Right-info.Window.Left) + 1
	rows = int(info.Window.Bottom-info.Window.Top) + 1
	if cols <= 0 {
		cols = 80
	}
	if rows <= 0 {
		rows = 24
	}
	return cols, rows
}

func readHandle(h uintptr, buf []byte) (int, error) {
	var done uint32
	err := syscall.ReadFile(syscall.Handle(h), buf, &done, nil)
	return int(done), err
}

func writeHandle(h uintptr, data []byte) {
	var done uint32
	for len(data) > 0 {
		if err := syscall.WriteFile(syscall.Handle(h), data, &done, nil); err != nil || done == 0 {
			return
		}
		data = data[done:]
	}
}

func copyFD(from, to uintptr, _ any) {
	buf := make([]byte, 4096)
	for {
		n, e := readHandle(from, buf)
		if n > 0 {
			writeHandle(to, buf[:n])
		}
		if e != nil || n == 0 {
			return
		}
	}
}

// ---- shared shim logic (mirrors claude-shim.py) ------------------------------

func execReal(args []string) int {
	cmd := exec.Command(resolveReal(), args...)
	cmd.Stdin, cmd.Stdout, cmd.Stderr = os.Stdin, os.Stdout, os.Stderr
	if err := cmd.Run(); err != nil {
		if ee, ok := err.(*exec.ExitError); ok {
			return ee.ExitCode()
		}
		return 1
	}
	return 0
}

func resolveReal() string {
	if override := os.Getenv("CLAUDEDRIVER_SHIM_TARGET"); override != "" {
		return override
	}
	self, _ := os.Executable()
	self, _ = filepath.EvalSymlinks(self)
	for _, dir := range filepath.SplitList(os.Getenv("PATH")) {
		for _, name := range []string{"claude.exe", "claude.cmd", "claude.bat", "claude"} {
			cand := filepath.Join(dir, name)
			if fi, err := os.Stat(cand); err == nil && !fi.IsDir() {
				if rp, _ := filepath.EvalSymlinks(cand); rp != self {
					return cand
				}
			}
		}
	}
	return "claude.exe"
}
