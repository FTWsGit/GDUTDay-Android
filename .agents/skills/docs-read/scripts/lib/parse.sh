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

    # Parse front matter in one awk pass (spawning per-line sed/cut is
    # extremely slow on Windows Git Bash: each fork costs ~250ms).
    _pf_parsed=$(awk '
        NR == 1 { ok = ($0 == "---"); next }
        /^---$/ { stop = 1; next }
        !stop {
            line = $0
            sub(/^[[:space:]]+/, "", line)
            eq = index(line, ":")
            if (eq > 0) {
                key = substr(line, 1, eq - 1)
                val = substr(line, eq + 1)
                if (key !~ /^[a-zA-Z_][a-zA-Z_0-9]*$/) next
                sub(/^[[:space:]]+/, "", val)
                gsub(/^["\x27]|["\x27]$/, "", val)
                if (key == "name") name = val
                else if (key == "description") desc = val
                else if (key == "alwaysApply") {
                    v = tolower(val)
                    if (v == "true" || v == "1") always = "true"
                    else always = "false"
                }
            }
        }
        END {
            if (!ok) exit 3
            if (name == "") exit 4
            print "name=" name
            print "description=" desc
            print "alwaysApply=" always
        }
    ' "$_pf_file" 2>/dev/null)
    _pf_rc=$?
    case "$_pf_rc" in
        0) printf '%s\n' "$_pf_parsed" ;;
        3) echo "warn: $_pf_file has no front matter" >&2 ;;
        4) echo "warn: $_pf_file missing required field: name" >&2 ;;
        *) : ;;  # empty/unreadable file: awk outputs nothing, stay silent
    esac
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
#
# All files are parsed in ONE awk invocation: on Windows Git Bash each
# fork costs ~0.2-1s, so per-file awk calls dominate runtime.
# This text format is kept for create_docs.sh's self-check (parse_file);
# batch consumers should use list_docs_json.
list_docs() {
    _ld_dir="${1:-docs}"
    _ld_base="$_ld_dir"

    walk_mdc "$_ld_dir" "$_ld_base" > /dev/null  # ensure dir exists check
    # shellcheck disable=SC2046
    set -- $(find "$_ld_base" -name '*.mdc' -type f 2>/dev/null | sort)
    [ $# -gt 0 ] || return 0
    awk -v base="$_ld_base" '
        function flush() {
            if (name != "") {
                print "name=" name
                print "description=" desc
                print "alwaysApply=" always
                print "file=" rel
                print ""
            }
            name = ""; desc = ""; always = "false"
        }
        BEGINFILE {
            rel = FILENAME
            sub("^" base "/", "", rel)
            gsub(/\\/, "/", rel)
        }
        FNR == 1 { stop = 0; ok = ($0 == "---"); next }
        /^---$/ { stop = 1; next }
        !stop {
            line = $0
            sub(/^[[:space:]]+/, "", line)
            eq = index(line, ":")
            if (eq > 0) {
                key = substr(line, 1, eq - 1)
                val = substr(line, eq + 1)
                if (key !~ /^[a-zA-Z_][a-zA-Z_0-9]*$/) next
                sub(/^[[:space:]]+/, "", val)
                gsub(/^["\x27]|["\x27]$/, "", val)
                if (key == "name") name = val
                else if (key == "description") { desc = val; gsub(/\\/, "\\\\", desc); gsub(/"/, "\\\"", desc) }
                else if (key == "alwaysApply") {
                    v = tolower(val)
                    always = (v == "true" || v == "1") ? "true" : "false"
                }
            }
        }
        ENDFILE { flush() }
        END { flush() }
    ' "$@"
}

# list_docs_json <directory>
# Same as list_docs but emits a JSON array (list_docs.sh output format).
list_docs_json() {
    _ld_dir="${1:-docs}"
    _ld_base="$_ld_dir"

    walk_mdc "$_ld_dir" "$_ld_base" > /dev/null  # ensure dir exists check
    # shellcheck disable=SC2046
    set -- $(find "$_ld_base" -name '*.mdc' -type f 2>/dev/null | sort)
    printf '[\n'
    [ $# -gt 0 ] || { printf ']\n'; return 0; }
    awk -v base="$_ld_base" '
        function flush() {
            if (name != "" && rec > 0) printf ",\n"
            if (name != "")
                printf "  {\"file\": \"%s\", \"name\": \"%s\", \"description\": \"%s\", \"alwaysApply\": %s}", rel, name, desc, always
            if (name != "") rec++
            name = ""; desc = ""; always = "false"
        }
        BEGINFILE {
            rel = FILENAME
            sub("^" base "/", "", rel)
            gsub(/\\/, "/", rel)
        }
        FNR == 1 { stop = 0; ok = ($0 == "---"); next }
        /^---$/ { stop = 1; next }
        !stop {
            line = $0
            sub(/^[[:space:]]+/, "", line)
            eq = index(line, ":")
            if (eq > 0) {
                key = substr(line, 1, eq - 1)
                val = substr(line, eq + 1)
                if (key !~ /^[a-zA-Z_][a-zA-Z_0-9]*$/) next
                sub(/^[[:space:]]+/, "", val)
                gsub(/^["\x27]|["\x27]$/, "", val)
                if (key == "name") name = val
                else if (key == "description") { desc = val; gsub(/\\/, "\\\\", desc); gsub(/"/, "\\\"", desc) }
                else if (key == "alwaysApply") {
                    v = tolower(val)
                    always = (v == "true" || v == "1") ? "true" : "false"
                }
            }
        }
        ENDFILE { flush() }
        END { flush(); printf "\n" }
    ' "$@"
    printf ']\n'
}

# read_full <file_path>
# Read the full content of a file (including front matter).
read_full() {
    cat "$1" 2>/dev/null
}
