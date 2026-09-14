#!/bin/sh
# Shared front matter parser: list/print/create three entry points reuse.
# Only recognizes YAML front matter (--- delimited), reads name/description/alwaysApply.
# alwaysApply defaults to false. Invalid front matter → skip with stderr warn, won't abort batch.

# parse_file <file_path>
# Parse a single .mdc file's front matter. Outputs "key=value" lines on stdout.
# Outputs nothing on failure.
parse_file() {
    _pf_file="$1"
    if [ ! -f "$_pf_file" ]; then
        echo "warn: cannot read $_pf_file: no such file" >&2
        return
    fi

    _pf_text=$(cat "$_pf_file" 2>/dev/null)
    if [ -z "$_pf_text" ]; then
        echo "warn: $_pf_file is empty" >&2
        return
    fi

    _pf_first_line=$(printf '%s\n' "$_pf_text" | head -n 1)
    if [ "$_pf_first_line" != "---" ]; then
        echo "warn: $_pf_file has no front matter" >&2
        return
    fi

    # Extract lines between first --- and second --- (exclusive)
    _pf_fm=$(printf '%s\n' "$_pf_text" | sed -n '2,/^---$/p' | sed '$d')
    if [ -z "$_pf_fm" ]; then
        echo "warn: $_pf_file front matter not terminated" >&2
        return
    fi

    # Parse key: value pairs using sed
    _pf_name=""
    _pf_desc=""
    _pf_always="false"

    while IFS= read -r _pf_line; do
        _pf_kv=$(printf '%s\n' "$_pf_line" | sed -n 's/^[[:space:]]*\([a-zA-Z_][a-zA-Z_0-9]*\):[[:space:]]*\(.*\)/\1|\2/p')
        if [ -n "$_pf_kv" ]; then
            _pf_key=$(printf '%s\n' "$_pf_kv" | cut -d'|' -f1)
            _pf_val=$(printf '%s\n' "$_pf_kv" | cut -d'|' -f2-)
            # Strip surrounding quotes
            _pf_val=$(printf '%s\n' "$_pf_val" | sed 's/^"//;s/"$//' | sed "s/^'//;s/'$//")
            case "$_pf_key" in
                name) _pf_name="$_pf_val" ;;
                description) _pf_desc="$_pf_val" ;;
                alwaysApply)
                    case "$(printf '%s\n' "$_pf_val" | tr '[:upper:]' '[:lower:]')" in
                        true|1) _pf_always="true" ;;
                    esac
                    ;;
            esac
        fi
    done <<EOF
$_pf_fm
EOF

    if [ -z "$_pf_name" ]; then
        echo "warn: $_pf_file missing required field: name" >&2
        return
    fi

    echo "name=$_pf_name"
    echo "description=$_pf_desc"
    echo "alwaysApply=$_pf_always"
}

# walk_mdc <directory> [<base>]
# Recursively enumerate all .mdc files under directory, relative to base.
# Uses / as separator. Returns one path per line, sorted.
walk_mdc() {
    _wm_dir="$1"
    _wm_base="${2:-$1}"

    if [ ! -d "$_wm_dir" ]; then
        echo "error: cannot readdir $_wm_dir: no such directory" >&2
        exit 1
    fi

    find "$_wm_dir" -name '*.mdc' -type f 2>/dev/null | while IFS= read -r _wm_full; do
        _wm_rel=$(printf '%s\n' "$_wm_full" | sed "s|${_wm_base}/||" | tr '\\' '/')
        printf '%s\n' "$_wm_rel"
    done | sort
}

# list_docs <directory>
# Enumerate all .mdc files, parse front matter.
# alwaysApply:true first, then by relative path.
# Outputs blank-line-separated records of "key=value" lines.
list_docs() {
    _ld_dir="${1:-docs}"
    _ld_base="$_ld_dir"

    walk_mdc "$_ld_dir" "$_ld_base" | while IFS= read -r _ld_rel; do
        _ld_output=$(parse_file "$_ld_base/$_ld_rel")
        if [ -n "$_ld_output" ]; then
            printf '%s\n' "$_ld_output"
            printf 'file=%s\n' "$_ld_rel"
            printf '\n'
        fi
    done
}

# read_full <file_path>
# Read the full content of a file (including front matter).
read_full() {
    cat "$1" 2>/dev/null
}
