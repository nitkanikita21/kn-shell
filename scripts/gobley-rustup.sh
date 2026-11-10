#!/bin/sh
# Gobley invokes `rustup` to register the Cargo target. In this repository the Rust
# toolchain is local (.rustup / .cargo-home) and not on the system PATH when Gradle is
# launched by an IDE. This wrapper always uses the repository toolchain when present and
# falls back to the system rustup otherwise.
set -e

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

if [ -x "$root/.cargo-home/bin/rustup" ]; then
    RUSTUP_HOME="$root/.rustup"
    CARGO_HOME="$root/.cargo-home"
    export RUSTUP_HOME CARGO_HOME
    exec "$CARGO_HOME/bin/rustup" "$@"
fi

exec rustup "$@"
