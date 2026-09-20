#!/bin/sh
# Print all alwaysApply:true documents' full content, stdout plain text.
# Phase 1 in SKILL.md uses it to read all core docs into context in one shot.
# Usage: sh scripts/print_always.sh [--dir <path>]

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

# Filter alwaysApply:true records from the JSON stream in one awk pass,
# then cat the files. No per-line forks.
_files=$(list_docs_json "$directory" | awk '
    match($0, /"file": "([^"]*)"/, m)  { f = m[1] }
    match($0, /"alwaysApply": (true|false)/, m) {
        if (m[1] == "true" && f != "") print f
        f = ""
    }
')

if [ -z "$_files" ]; then
    echo "warn: no alwaysApply:true docs found" >&2
    exit 0
fi

printf '%s\n' "$_files" | while IFS= read -r _file; do
    [ -z "$_file" ] && continue
    printf '=== %s ===\n' "$_file"
    read_full "$directory/$_file"
    printf '\n'
done
