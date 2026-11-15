#!/usr/bin/env bash
set -euo pipefail

src_dir="$(cd "$(dirname "$0")" && pwd)"
python_executable="${PYTHON:-}"
if [[ -z "$python_executable" && -n "${VIRTUAL_ENV:-}" ]]; then
    python_executable="$VIRTUAL_ENV/bin/python"
fi
if [[ -n "$python_executable" ]]; then
    set -- "-DPython3_EXECUTABLE=$python_executable" "$@"
fi
cmake -S "$src_dir/dependencies" -B "$src_dir/build" "$@"
cmake --build "$src_dir/build" --parallel
