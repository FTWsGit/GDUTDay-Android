#!/bin/sh
# Scaffold a new docs/*.mdc document with proper front matter.
#
# Usage:
#   sh scripts/create_docs.sh <name> "<description>" --kind <kind> [--always] [--dir <path>] [--force]
#
#   <name>         Document name, used as filename (<name>.mdc) and front matter name field, no .mdc suffix
#   <description>  "When to read", must be single-line plain text
#   --kind <kind>  Document nature: guide / contract / spec / architecture / subsystem / decision
#   --always       Set alwaysApply: true (default false)
#   --dir <path>   Target directory, default docs
#   --force        Allow overwriting existing file (default reject)

_script_dir=$(cd "$(dirname "$0")" && pwd)
. "$_script_dir/lib/parse.sh"

directory="docs"
kind=""
always="false"
force="false"
_name=""
_desc=""
_pos_count=0

while [ $# -gt 0 ]; do
    case "$1" in
        --dir) directory="$2"; shift ;;
        --kind) kind="$2"; shift ;;
        --always) always="true" ;;
        --force) force="true" ;;
        *)
            case $_pos_count in
                0) _name="$1" ;;
                1) _desc="$1" ;;
            esac
            _pos_count=$((_pos_count + 1))
            ;;
    esac
    shift
done

if [ -z "$_name" ] || [ -z "$_desc" ]; then
    echo 'usage: create_docs.sh <name> "<description>" --kind <spec|architecture|subsystem|decision|contract> [--always] [--dir <path>] [--force]' >&2
    exit 1
fi

if [ -z "$kind" ]; then
    echo "warn: no --kind given, generated doc won't be categorized — consider adding one (contract/spec/architecture/subsystem/decision)" >&2
fi

# Validate
case "$kind" in
    *\"*) echo "error: kind cannot contain double quotes" >&2; exit 1 ;;
esac

case "$_desc" in
    *$'\n'*|*$'\r'*) echo "error: description must be single-line, no newlines" >&2; exit 1 ;;
esac

# Check .mdc suffix
_name_lower=$(printf '%s' "$_name" | tr '[:upper:]' '[:lower:]')
case "$_name_lower" in
    *.mdc) echo "error: <name> should not include .mdc suffix, script adds it automatically" >&2; exit 1 ;;
esac

case "$_name" in
    *\"*) echo "error: name cannot contain double quotes" >&2; exit 1 ;;
esac

# Sanitize description
_safe_desc="$_desc"
case "$_desc" in
    *\"*)
        _safe_desc=$(printf '%s' "$_desc" | sed 's/"/'"'"'/g')
        echo "warn: double quotes in description auto-replaced with single quotes (front matter uses double quotes to wrap, parser doesn't support escaping)" >&2
        ;;
esac

# Check directory
if [ ! -d "$directory" ]; then
    echo "error: directory $directory does not exist" >&2
    exit 1
fi

_file_path="${directory}/${_name}.mdc"

if [ -f "$_file_path" ] && [ "$force" != "true" ]; then
    echo "error: $_file_path already exists, add --force to allow overwrite" >&2
    exit 1
fi

# Write the file
cat > "$_file_path" <<ENDOFFILE
---
name: "${_name}"
kind: "${kind}"
description: "${_safe_desc}"
alwaysApply: ${always}
---

# ${_name}

ENDOFFILE

# Self-check
_check_name=""
_check_desc=""
_check_always=""
_check_output=$(parse_file "$_file_path" 2>/dev/null)
if [ -n "$_check_output" ]; then
    _check_name=$(printf '%s\n' "$_check_output" | sed -n 's/^name=//p')
    _check_desc=$(printf '%s\n' "$_check_output" | sed -n 's/^description=//p')
    _check_always=$(printf '%s\n' "$_check_output" | sed -n 's/^alwaysApply=//p')
fi

if [ -z "$_check_name" ]; then
    echo "error: generated $_file_path failed front matter self-check" >&2
    exit 1
fi

echo "created $_file_path"
echo "  name: $_check_name"
echo "  kind: ${kind:-"(not set)"}"
echo "  description: $_check_desc"
echo "  alwaysApply: $_check_always"
echo ""
echo "Next step: fill in body content, can append directly to end of file"
