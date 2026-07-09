# SPDX-FileCopyrightText: The tik Authors
# SPDX-License-Identifier: 0BSD
{
  description = "tik — a data-driven, decentralized process system (not a ticket system)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.05";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
      in
      {
        devShells.default = pkgs.mkShell {
          packages = with pkgs; [
            jdk21          # JVM for the full library / server work
            clojure        # tools.deps CLI (tests, REPL)
            babashka       # the tik CLI runtime
            clj-kondo      # linting
            tlaplus        # TLC model checker for the specs in spec/
            git            # storage & replication substrate
            reuse          # SPDX/REUSE compliance checks
            sqlite         # embedded store tier (Phase 2 spike)
          ];

          shellHook = ''
            echo "tik dev shell — try:"
            echo "  bb tik            # CLI usage"
            echo "  bb test           # JVM test suite (kaocha)"
            echo "  bb lint           # clj-kondo"
            echo "  bb tla            # model-check the TLA+ specs"
            echo "  reuse lint        # license compliance"
          '';
        };
      });
}
