#!/usr/bin/env bash
set -euo pipefail

# Resolve the JAR next to this script so ./run.sh works from any working directory.
# Input paths are passed through unchanged, relative to the caller's directory.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec java -jar "$SCRIPT_DIR/target/obsi-calculator.jar" "$@"
