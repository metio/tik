// SPDX-FileCopyrightText: The tik Authors
// SPDX-License-Identifier: 0BSD

package main

import (
	"io"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
)

// withUmask sets the process umask for one test and restores it afterwards,
// since run clears it whenever -m is given.
func withUmask(t *testing.T, mask int) {
	t.Helper()
	old := syscall.Umask(mask)
	t.Cleanup(func() { syscall.Umask(old) })
}

func fifoMode(t *testing.T, path string) os.FileMode {
	t.Helper()
	info, err := os.Lstat(path)
	if err != nil {
		t.Fatalf("lstat %s: %v", path, err)
	}
	if info.Mode().Type() != os.ModeNamedPipe {
		t.Fatalf("%s is %v, want a named pipe", path, info.Mode().Type())
	}
	return info.Mode().Perm()
}

func TestModeIsExactRegardlessOfUmask(t *testing.T) {
	dir := t.TempDir()
	withUmask(t, 0o077)
	a, b := filepath.Join(dir, "a"), filepath.Join(dir, "b")
	if code := run([]string{"-m", "644", a, b}, io.Discard); code != 0 {
		t.Fatalf("exit %d", code)
	}
	for _, p := range []string{a, b} {
		if got := fifoMode(t, p); got != 0o644 {
			t.Errorf("%s has mode %o, want 644", p, got)
		}
	}
}

func TestOwnerOnlyModeAsBrikoAsksForIt(t *testing.T) {
	withUmask(t, 0o022)
	p := filepath.Join(t.TempDir(), "out")
	if code := run([]string{"-m", "600", p}, io.Discard); code != 0 {
		t.Fatalf("exit %d", code)
	}
	if got := fifoMode(t, p); got != 0o600 {
		t.Errorf("mode %o, want 600", got)
	}
}

// The tests read modes from pipes in TMPDIR, which must honour the umask:
// rootless podman's overlay ignores it for mknod, so the image build runs
// them on a tmpfs.
func TestWithoutModeTheUmaskApplies(t *testing.T) {
	withUmask(t, 0o027)
	p := filepath.Join(t.TempDir(), "p")
	if code := run([]string{p}, io.Discard); code != 0 {
		t.Fatalf("exit %d", code)
	}
	if got := fifoMode(t, p); got != 0o640 {
		t.Errorf("mode %o, want 640", got)
	}
}

func TestDoubleDashEndsOptions(t *testing.T) {
	withUmask(t, 0o022)
	dir := t.TempDir()
	t.Chdir(dir)
	if code := run([]string{"-m", "600", "--", "-m"}, io.Discard); code != 0 {
		t.Fatalf("exit %d", code)
	}
	if got := fifoMode(t, filepath.Join(dir, "-m")); got != 0o600 {
		t.Errorf("mode %o, want 600", got)
	}
}

// Each case names the path to be checked as P.
func TestInvalidInvocationsCreateNothing(t *testing.T) {
	withUmask(t, 0o022)
	for _, args := range [][]string{
		{"-m", "999", "P"},
		{"-m", "8", "P"},
		{"-m", "1777", "P"},
		{"-m", "4755", "P"},
		{"-m", "u=rw", "P"},
		{"-m", "", "P"},
		{"-m", "00600", "P"},
		{"-m", "-1", "P"},
		{"-p", "P"},
		{"-m"},
		{"-m", "600"},
		{},
	} {
		p := filepath.Join(t.TempDir(), "p")
		argv := make([]string, len(args))
		for i, a := range args {
			argv[i] = strings.ReplaceAll(a, "P", p)
		}
		var stderr strings.Builder
		if code := run(argv, &stderr); code != 1 {
			t.Errorf("%q: exit %d, want 1", args, code)
		}
		if !strings.Contains(stderr.String(), "usage:") {
			t.Errorf("%q: stderr %q lacks the usage line", args, stderr.String())
		}
		if _, err := os.Lstat(p); !os.IsNotExist(err) {
			t.Errorf("%q: %s exists after a refused invocation", args, p)
		}
	}
}

func TestEveryPathIsAttemptedAndAFailureIsReported(t *testing.T) {
	withUmask(t, 0o022)
	dir := t.TempDir()
	taken := filepath.Join(dir, "taken")
	if err := os.WriteFile(taken, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	later := filepath.Join(dir, "later")
	var stderr strings.Builder
	if code := run([]string{"-m", "600", taken, later}, &stderr); code != 1 {
		t.Fatalf("exit %d, want 1", code)
	}
	if !strings.Contains(stderr.String(), taken) {
		t.Errorf("stderr %q does not name %s", stderr.String(), taken)
	}
	if got := fifoMode(t, later); got != 0o600 {
		t.Errorf("mode %o, want 600", got)
	}
}
