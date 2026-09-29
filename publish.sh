#!/usr/bin/env bash
# Publishes the 0.1.0 release with GitHub's CLI (sudo dnf install gh; gh auth login).
# Run it from this folder, inside or next to your MiracleLoader clone.
set -euo pipefail
cd "$(dirname "$0")"
gh release create v0.1.0 \
    --repo Hronosin/MiracleLoader \
    --target 5aa78242ee3e965f9435cc4d856fdafa76df24d8 \
    --title "MiracleLoader 0.1.0: it just happens" \
    --notes-file notes.md \
    miracle-toolchain-0.1.0.zip miracle-loader-0.1.0.jar \
    dirt-diamonds-0.1.0.jar super-jump-0.1.0.jar sprint-jump-0.1.0.jar jump-counter-0.1.0.jar
