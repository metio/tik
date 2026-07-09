# SPDX-FileCopyrightText: The tik Authors
# SPDX-License-Identifier: 0BSD
{
  description = "tik — a data-driven, decentralized process system (not a ticket system)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.05";
    flake-utils.url = "github:numtide/flake-utils";
    # the metio toolchain: shared lint gate (reuse, typos, yamllint,
    # actionlint, markdownlint-cli2, …) plus the ci-* wrappers CI runs
    devshell.url = "github:metio/nix-devshell";
  };

  outputs = { self, nixpkgs, flake-utils, devshell }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
      in
      {
        devShells.default = devshell.lib.mkDevShell {
          inherit pkgs;
          packages = with pkgs; [
            jdk21          # JVM for the full library / server work
            clojure        # tools.deps CLI (tests, deep analysis, REPL)
            babashka       # the tik CLI runtime
            clj-kondo      # fast lint (the deep battery rides deps.edn aliases)
            tlaplus        # TLC model checker for the specs in spec/
            # harper-cli (bb prose) arrives via the shared devshell's
            # ci-harper once metio/nix-devshell ships it (25.05 nixpkgs
            # only packages harper-ls)
            git            # storage & replication substrate
            sqlite         # embedded store tier (Phase 2 spike)
          ];

          menu = ''
            echo "tik dev shell — try:"
            echo "  bb tik            # CLI usage"
            echo "  bb test           # JVM test suite (kaocha)"
            echo "  bb lint           # clj-kondo"
            echo "  bb analyze        # eastwood + splint"
            echo "  bb fmt            # cljfmt check (bb fmt fix rewrites)"
            echo "  bb vuln           # clj-watson CVE scan (needs GITHUB_TOKEN)"
            echo "  bb tla            # model-check the TLA+ specs"
            echo "  bb prose          # harper grammar over docs/ and kb/ (advisory)"
          '';
        };
      });
}
