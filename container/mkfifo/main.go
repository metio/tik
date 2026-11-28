// SPDX-FileCopyrightText: The tik Authors
// SPDX-License-Identifier: 0BSD

// Command mkfifo creates named pipes. It is the only tool besides tik in the
// container image, and it exists so that briko.process can create its
// owner-only output pipes there with `mkfifo -m 600 PATH...`.
//
//	mkfifo [-m MODE] [--] PATH...
//
// MODE is an octal permission mode no greater than 0777; symbolic modes and
// the setuid, setgid and sticky bits are refused. With -m the pipe receives
// exactly MODE regardless of the umask, as with coreutils, but in a single
// mkfifo(2) call, so no other mode is visible at any moment. Without -m the
// pipe is created with 0666 less the umask. An invalid invocation creates
// nothing and exits 1; otherwise every PATH is attempted and the exit status
// is 1 when any of them failed.
package main

import (
	"fmt"
	"io"
	"os"
	"strconv"
	"syscall"
)

func main() {
	os.Exit(run(os.Args[1:], os.Stderr))
}

func run(args []string, stderr io.Writer) int {
	mode, paths, err := parse(args)
	if err != nil {
		fmt.Fprintf(stderr, "mkfifo: %v\nusage: mkfifo [-m MODE] [--] PATH...\n", err)
		return 1
	}
	if mode >= 0 {
		syscall.Umask(0)
	} else {
		mode = 0o666
	}
	status := 0
	for _, path := range paths {
		if err := syscall.Mkfifo(path, uint32(mode)); err != nil {
			fmt.Fprintf(stderr, "mkfifo: cannot create fifo %q: %v\n", path, err)
			status = 1
		}
	}
	return status
}

// parse returns the requested mode, or -1 when none was given, and the paths.
func parse(args []string) (int64, []string, error) {
	mode := int64(-1)
	for len(args) > 0 && len(args[0]) > 1 && args[0][0] == '-' {
		arg := args[0]
		if arg == "--" {
			args = args[1:]
			break
		}
		if arg != "-m" {
			return 0, nil, fmt.Errorf("unknown option %q", arg)
		}
		if len(args) < 2 {
			return 0, nil, fmt.Errorf("option -m needs a mode")
		}
		m, err := parseMode(args[1])
		if err != nil {
			return 0, nil, err
		}
		mode = m
		args = args[2:]
	}
	if len(args) == 0 {
		return 0, nil, fmt.Errorf("missing operand")
	}
	return mode, args, nil
}

func parseMode(s string) (int64, error) {
	if s == "" || len(s) > 4 {
		return 0, fmt.Errorf("invalid mode %q", s)
	}
	for _, c := range s {
		if c < '0' || c > '7' {
			return 0, fmt.Errorf("invalid mode %q", s)
		}
	}
	m, err := strconv.ParseInt(s, 8, 64)
	if err != nil || m > 0o777 {
		return 0, fmt.Errorf("invalid mode %q", s)
	}
	return m, nil
}
