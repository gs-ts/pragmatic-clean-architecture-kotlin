#!/usr/bin/env bash
# Regenerates the always-on instruction files in ide-configs/ from SKILL.md, so SKILL.md
# stays the single source of truth. The files contain SKILL.md's "Ground rules" and
# "Checklist" sections (the text between the BEGIN/END markers).
#
# Usage:
#   scripts/build-adapters.sh          # regenerate ide-configs/
#   scripts/build-adapters.sh --check  # exit 1 if ide-configs/ is out of date (for CI)
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
skill="$root/skills/pragmatic-clean-architecture-kotlin/SKILL.md"
targets=("$root/ide-configs/copilot-instructions.md" "$root/ide-configs/AGENTS.md")

# Prints the lines between "<!-- BEGIN $1" and "<!-- END $1"; fails if the marker is missing.
extract() {
  awk -v begin="<!-- BEGIN $1" -v end="<!-- END $1" '
    index($0, begin) == 1 { inside = 1; found = 1; next }
    index($0, end) == 1   { inside = 0; next }
    inside                { print }
    END                   { if (!found) exit 1 }
  ' "$skill" || { echo "error: marker '$1' not found in $skill" >&2; exit 1; }
}

render() {
  cat <<'HEADER'
<!-- Generated from skills/pragmatic-clean-architecture-kotlin/SKILL.md by scripts/build-adapters.sh.
     Edit SKILL.md and re-run the script instead of editing this file. -->
# Kotlin / Android / KMP Architecture Rules

Pragmatic Clean Architecture for Kotlin client code: boundaries where they protect you, no ceremony where they don't. Each rule is a default with stated exceptions. The full guide, with references and examples, is the `pragmatic-clean-architecture-kotlin` skill.

HEADER
  extract GROUND-RULES
  echo
  extract CHECKLIST
}

if [[ "${1:-}" == "--check" ]]; then
  expected="$(render)"
  status=0
  for target in "${targets[@]}"; do
    if [[ "$(cat "$target" 2>/dev/null)" != "$expected" ]]; then
      echo "out of date: ${target#"$root"/}" >&2
      status=1
    fi
  done
  if [[ $status -eq 0 ]]; then echo "ide-configs/ is up to date."; fi
  exit $status
fi

for target in "${targets[@]}"; do
  render > "$target"
  echo "wrote ${target#"$root"/}"
done
