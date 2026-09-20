#!/bin/sh
# Enumerate docs front matter, stdout outputs JSON-like records.
# Each record: {file, name, description, alwaysApply}. alwaysApply:true first, then by filename.
# Usage: sh scripts/list_docs.sh [--dir <path>]
#
# Parsing + JSON assembly happen in ONE awk pass (see lib/parse.sh):
# per-line sed/cut forks cost ~0.2-1s each on Windows Git Bash.

_script_dir=$(cd "$(dirname "$0")" && pwd)
. "$_script_dir/lib/parse.sh"

directory="docs"

for arg in "$@"; do
    if [ "$_next_is_dir" = "1" ]; then
        directory="$arg"
        _next_is_dir=0
        continue
    fi
    case "$arg" in
        --dir) _next_is_dir=1 ;;
    esac
done

list_docs_json "$directory"
