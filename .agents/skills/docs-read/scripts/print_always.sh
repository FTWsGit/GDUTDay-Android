#!/bin/sh
# Print all alwaysApply:true documents' full content, stdout plain text.
# Phase 1 in SKILL.md uses it to read all core docs into context in one shot.
# Usage: sh scripts/print_always.sh [--dir <path>]

_script_dir=$(cd "$(dirname "$0")" && pwd)
. "$_script_dir/lib/parse.sh"

args=""
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

_tmp=$(mktemp 2>/dev/null || echo "/tmp/print_always_$$")
trap 'rm -f "$_tmp"' EXIT

list_docs "$directory" > "$_tmp" 2>/dev/null

# Collect alwaysApply:true entries
_always_files=""
_current_file=""
_current_always="false"

while IFS= read -r _line; do
    if [ -z "$_line" ]; then
        if [ "$_current_always" = "true" ] && [ -n "$_current_file" ]; then
            _always_files="$_always_files$_current_file"$'\n'
        fi
        _current_file=""
        _current_always="false"
        continue
    fi

    _key=$(printf '%s' "$_line" | cut -d'=' -f1)
    _val=$(printf '%s' "$_line" | cut -d'=' -f2-)
    case "$_key" in
        file) _current_file="$_val" ;;
        alwaysApply) _current_always="$_val" ;;
    esac
done < "$_tmp"

# Handle last record
if [ "$_current_always" = "true" ] && [ -n "$_current_file" ]; then
    _always_files="$_always_files$_current_file"$'\n'
fi

if [ -z "$_always_files" ]; then
    echo "warn: no alwaysApply:true docs found" >&2
    exit 0
fi

# Print each file's full content
printf '%s' "$_always_files" | while IFS= read -r _file; do
    [ -z "$_file" ] && continue
    printf '=== %s ===\n' "$_file"
    read_full "$directory/$_file"
    printf '\n'
done
