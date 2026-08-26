#!/usr/bin/env bash
# export-transcripts.sh: copy raw Codex transcripts into transcripts/ for
# this repository.
#
# What it does:
#   1. Searches Codex's active and archived session directories.
#   2. Selects sessions whose recorded working directory is this repository
#      (or one of its subdirectories).
#   3. Copies the complete JSONL session logs into transcripts/ unchanged.
#
# Usage (from anywhere inside your course repository):
#   ./tools/export-transcripts.sh
#
# Notes:
#   - Codex Desktop, the Codex CLI, and Codex subagents use the same session
#     storage, so the same export covers all three.
#   - Sessions are stored on the machine where they ran. Run this script on
#     each machine if you used more than one.
#   - Finish or exit relevant Codex sessions before the final export so their
#     JSONL files are no longer being written.
#   - Review exported files for accidentally personal content before sharing.
#
# INVARIANT: this script exports JSONL files only from sessions/ and
# archived_sessions/. It must never copy Codex auth, config, state, or memory
# data from elsewhere under CODEX_HOME.

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || {
    echo "error: this is not a git repository. Run the script from inside your course repository." >&2
    exit 1
}

CODEX_DIR="${CODEX_HOME:-$HOME/.codex}"
search_roots=()
for candidate in "$CODEX_DIR/sessions" "$CODEX_DIR/archived_sessions"; do
    if [ -d "$candidate" ]; then
        search_roots+=("$candidate")
    fi
done

if [ "${#search_roots[@]}" -eq 0 ]; then
    echo "error: no Codex session directories found under $CODEX_DIR." >&2
    echo "Start Codex inside this repository, finish a session, and try again." >&2
    exit 1
fi

DEST="$REPO_ROOT/transcripts"
mkdir -p "$DEST"

copied=0
skipped=0

while IFS= read -r -d '' session_file; do
    # The structured session metadata records cwd as compact JSON. Matching the
    # exact root and root/ forms avoids including a sibling such as repo-extra.
    if grep -q -m 1 -F \
        -e "\"cwd\":\"$REPO_ROOT\"" \
        -e "\"cwd\":\"$REPO_ROOT/" \
        "$session_file"; then
        cp -p "$session_file" "$DEST/$(basename "$session_file")"
        copied=$((copied + 1))
    else
        skipped=$((skipped + 1))
    fi
done < <(find "${search_roots[@]}" -type f -name '*.jsonl' -print0)

if [ "$copied" -eq 0 ]; then
    echo "No Codex sessions found for $REPO_ROOT." >&2
    echo "Start Codex with this repository as its working directory and try again." >&2
    if [ "$skipped" -gt 0 ]; then
        echo "(Skipped $skipped session file(s) from other working directories.)" >&2
    fi
    exit 1
fi

echo "Exported $copied session(s) to transcripts/ ($(du -sh "$DEST" | cut -f1) total)."
if [ "$skipped" -gt 0 ]; then
    echo "Skipped $skipped session file(s) from other working directories."
fi

echo
echo "Next steps:"
echo "  1. Skim the exported files for accidentally personal content."
if git check-ignore -q "$DEST"; then
    echo "  2. transcripts/ is ignored in this public repository; keep it local."
    echo "  3. Do not commit or push these transcripts. Show them to the TA locally."
else
    echo "  2. Follow the assignment handout's transcript submission instructions."
fi
