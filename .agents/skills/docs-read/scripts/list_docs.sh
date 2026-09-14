#!/bin/sh
# Enumerate docs front matter, stdout outputs JSON-like records.
# Each record: {file, name, description, alwaysApply}. alwaysApply:true first, then by filename.
# Usage: sh scripts/list_docs.sh [--dir <path>]

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

# Collect all records into a temporary file
_tmp=$(mktemp 2>/dev/null || echo "/tmp/list_docs_$$")
 trap 'rm -f "$_tmp"' EXIT

list_docs "$directory" > "$_tmp" 2>/dev/null

# Parse records and output JSON array
printf '[\n'
_first=true
_current_file=""
_current_name=""
_current_desc=""
_current_always="false"

while IFS= read -r _line; do
    if [ -z "$_line" ]; then
        # End of record — emit if we have data
        if [ -n "$_current_name" ]; then
            if [ "$_first" = "true" ]; then
                _first=false
            else
                printf ',\n'
            fi
            # Escape quotes in description for JSON
            _esc_desc=$(printf '%s' "$_current_desc" | sed 's/"/\\"/g')
            printf '  {"file": "%s", "name": "%s", "description": "%s", "alwaysApply": %s}' \
                "$_current_file" "$_current_name" "$_esc_desc" "$_current_always"
        fi
        _current_file=""
        _current_name=""
        _current_desc=""
        _current_always="false"
        continue
    fi

    _key=$(printf '%s' "$_line" | cut -d'=' -f1)
    _val=$(printf '%s' "$_line" | cut -d'=' -f2-)
    case "$_key" in
        file) _current_file="$_val" ;;
        name) _current_name="$_val" ;;
        description) _current_desc="$_val" ;;
        alwaysApply) _current_always="$_val" ;;
    esac
done < "$_tmp"

# Emit last record if file doesn't end with newline
if [ -n "$_current_name" ]; then
    if [ "$_first" = "true" ]; then
        _first=false
    else
        printf ',\n'
    fi
    _esc_desc=$(printf '%s' "$_current_desc" | sed 's/"/\\"/g')
    printf '  {"file": "%s", "name": "%s", "description": "%s", "alwaysApply": %s}' \
        "$_current_file" "$_current_name" "$_esc_desc" "$_current_always"
fi

printf '\n]\n'
