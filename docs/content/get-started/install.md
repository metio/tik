---
title: Install
description: Run the native binary or the uberjar, run from source with babashka, or work inside the nix devshell.
tags: [install, binary, babashka, nix]
---

Each release publishes two downloads, a `SHA256SUMS` file covering both,
and a cosign keyless signature over those checksums.

## Download a release

From the [releases page](https://github.com/metio/tik/releases):

- **`tik-linux-amd64-glibc`** — the native binary, no runtime needed. It is
  labelled for exactly what it is: linux/amd64 against glibc.
- **`tik.jar`** — the uberjar, for macOS, Windows, and arm. Runs on any
  JDK 21: `java -jar tik.jar --help`. The container image is the universal
  deployment path.

```sh
chmod +x tik-linux-amd64-glibc
./tik-linux-amd64-glibc --help
```

Verify before trusting. The checksums cover the downloads, and the cosign
bundle covers the checksums:

```sh
sha256sum --check SHA256SUMS
cosign verify-blob SHA256SUMS \
  --bundle SHA256SUMS.bundle \
  --certificate-identity-regexp '^https://github.com/metio/tik/' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

## From source with babashka

The CLI runs on [babashka](https://babashka.org/), so a checkout is enough:

```sh
git clone https://github.com/metio/tik.git
cd tik
bb tik --help
```

Every command in this documentation is written as `tik`; substitute
`bb tik` when running this way.

## The nix devshell

Contributors get the whole toolchain — JVM, Clojure, babashka, clj-kondo,
TLC, GraalVM, ssh-keygen — from the flake:

```sh
nix develop --command bb test
```

[Contributing](/contributing/) describes the gate a change is expected to
pass.

## Signing your writes

A store works unsigned, and signing turns authorship into evidence anyone
can check offline. Register yourself as an actor and point `TIK_KEY` at an
ed25519 private key:

```sh
ssh-keygen -t ed25519 -f ~/.config/tik/id_ed25519
tik actor add alice ~/.config/tik/id_ed25519.pub
export TIK_KEY=~/.config/tik/id_ed25519
export TIK_ACTOR=alice
```

Signatures are detached sidecars produced by `ssh-keygen -Y`, so `tik
verify` checks them with stock OpenSSH and nothing else. The public
registry (`actors`) belongs in version control; the private key stays
outside the store.

## Where a store lives

Commands find a store the way git does: `TIK_ROOT` wins, otherwise the
nearest ancestor directory holding `tickets/`, `tik.db`, or `.tik/`,
otherwise the current directory. `tik init` marks one explicitly —
`--sqlite` for the single-file backend, `--hidden` to keep everything
inside `.tik/` when the store sits above several repositories.
