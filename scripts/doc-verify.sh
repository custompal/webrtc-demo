#!/usr/bin/env bash
# =============================================================================
# doc-verify.sh — the documentation delivery gate (task t2, owner doc-tooling).
#
# Verifies that what the documents claim actually exists: source citations,
# symbols, protocol message types, internal links, legacy stubs, the canonical
# directory conventions, path classification (P1..P5), Gradle/script command
# names, and — since the i18n round — the bilingual document layout (V14/V15).
#
# Normative sources (frozen, owned by architect):
#   * doc/design/SPEC.md §2.3 (path vocabulary, T1..T6)
#   * doc/design/SPEC.md §7   (rules V1..V13, ambiguity handling A1..A11)
#   * doc/design/SPEC.md §7.1 (path classification P1..P5)
#   * doc/design/SPEC.md §7.2 (scope notation syntax)
#   * doc/design/SPEC.md §7.3 (command-name classification)
#   * doc/design/SPEC.md §7.5 (this script's mechanism notes)
# If this implementation and the SPEC tables disagree, the tables are
# authoritative for intent; the divergence is reported to architect.
#
# Revision provenance. This revision supersedes
#   `676d075a067e869e9730afd71239f078205b1fd8043453e8c559c0f6ee8b1b45`
#   (the pre-i18n gate: 628 lines, 26545 bytes, frozen 2026-09-17 14:22:14 +0800).
#   The additions are the zh-CN scan set, V14 and V15; the task output carries
#   this revision's own sha256/bytes/lines/mtime plus the full diff list.
#
# i18n additions (V14/V15) — normative source: doc/design/zh-CN/GLOSSARY.md
# v1.1.1 §3 and §4 (Y-1..Y-4): its rule content is the frozen v1.1.0 text
# byte-for-byte, and v1.1.1 only synchronised §8.2 to SPEC v1.7.0. Registered
# as V14/V15 in SPEC v1.7.0 §7 (reading fixed by §7.6, mechanism by §7.5). The
# earlier v1.0.0 wording (disclaimer without the colon and 「原文」, a retained
# `README.zh-CN.md`) is superseded and must not be re-implemented.
#   * V14 `missing language switcher` — every frozen pair carries the switcher
#     in both directions, inside the first $SWITCHER_WINDOW lines, in the exact
#     frozen shape (SPEC §7.6, GLOSSARY §3.2/§3.3): the Chinese default page
#     writes `> **中文（默认）** · [English](<relative path>)`, the English page
#     writes `> [中文（默认）](<relative path>) · English`, and each link target
#     resolves to the counterpart on disk. A link that renders nothing (the
#     full-width bracket form) therefore fails V14. Extra content on the
#     switcher line is reported as a warning (GLOSSARY §3.1). Pairs:
#     README.md ↔ README.en.md, doc/design/README.md ↔ doc/design/README.en.md,
#     and doc/design/zh-CN/NN-*.md ↔ doc/design/NN-*.md (NN = 01..05).
#   * V15 `missing translation disclaimer` — every translated page on the §4
#     Y-1 list (doc/design/zh-CN/01-05 and zh-CN/SPEC-guide.md) states
#     `> 译文：若与英文原文冲突，以英文原文为准。` in its first 8 lines;
#     zh-CN/GLOSSARY.md, root README.md and doc/design/README.md are exempt
#     (Y-4).
#   * Duplicate-page diagnostic — advisory only, never a failure. SPEC v1.7.0 §7
#     registers V1-V15; this diagnostic carries no rule id. It derives from the
#     original t4 acceptance criterion of this i18n round (a byte-identical
#     `README.zh-CN.md`), which ruling A withdrew together with the copy itself.
#     The frozen layout keeps no language-suffixed copy of an entry/index page
#     (SPEC R12/§7.6; GLOSSARY v1.1.1 §2.1/§8.2), so the NOTE fires only when
#     README.zh-CN.md or doc/design/zh-CN/README.md exists; the copy's absence is
#     confirmed by independent verification (A6). Nothing here can fail the gate.
#   * A pair is armed as soon as one side carries an i18n marker (a zh-CN page
#     exists, or a page already links to its counterpart), so a full run on the
#     pre-i18n tree stays green while the pair is hard-checked once it lands.
#   * An `--only` run checks only the obligations of the files it was given, so
#     one writer's gate is not blocked by another writer's unfinished page.
#
# Usage
#   bash scripts/doc-verify.sh [--only <path>]...
#
#   --only <path>   restrict the run to a file or directory (repeatable, and
#                   accepts several paths after one flag). SPEC A5: the delivery
#                   gate for a writer task is
#                   `bash scripts/doc-verify.sh --only <its own files>`.
#
# Exit code: 0 only when there are no failures (SPEC A6). Findings are printed
# as `file:line → problem → suggested fix`.
#
# Reads only; never writes, never runs Gradle, never commits.
# =============================================================================
set -uo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$REPO_ROOT"

CONTAINER_ROOT="/data/dsh/home/workspace"
WORKSPACE_ROOT=$(cd "$REPO_ROOT/../.." && pwd)
GENERATED_DIR="doc/design/_generated"
SIGNALING_TABLE="$GENERATED_DIR/signaling-messages.md"
HOST_CMD_TABLE="$GENERATED_DIR/host-commands.md"
ZH_TREE="doc/design/zh-CN"
SWITCHER_WINDOW=8
DISCLAIMER='译文：若与英文原文冲突，以英文原文为准。'

FAILURES=0
WARNINGS=0
CHECKS=0

fail() { printf '%s:%s → %s → %s\n' "$1" "$2" "$3" "$4"; FAILURES=$((FAILURES + 1)); }
note() { printf 'NOTE %s:%s → %s\n' "$1" "$2" "$3"; }
warn() { printf 'WARN %s:%s → %s\n' "$1" "$2" "$3"; WARNINGS=$((WARNINGS + 1)); }

ONLY=()
while [ $# -gt 0 ]; do
  case "$1" in
    --only)
      shift
      while [ $# -gt 0 ] && [ "${1#--}" = "$1" ]; do ONLY+=("$1"); shift; done
      ;;
    --only=*) ONLY+=("${1#--only=}"); shift ;;
    -h|--help) sed -n '2,73p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) printf 'doc-verify.sh: unknown argument: %s\n' "$1" >&2; exit 2 ;;
  esac
done

# -----------------------------------------------------------------------------
# Target set
# -----------------------------------------------------------------------------
DOCS=()
if [ "${#ONLY[@]}" -gt 0 ]; then
  for p in "${ONLY[@]}"; do
    if [ -d "$p" ]; then
      while IFS= read -r f; do DOCS+=("$f"); done < <(find "$p" -name '*.md' -type f | sort)
    elif [ -f "$p" ]; then
      DOCS+=("$p")
    else
      printf 'doc-verify.sh: --only target does not exist: %s\n' "$p" >&2
      exit 2
    fi
  done
else
  while IFS= read -r f; do DOCS+=("$f"); done < <(find doc/design -maxdepth 1 -name '*.md' -type f | sort)
  if [ -d "$ZH_TREE" ]; then
    while IFS= read -r f; do DOCS+=("$f"); done < <(find "$ZH_TREE" -maxdepth 1 -name '*.md' -type f | sort)
  fi
  for f in README.md README.en.md README.zh-CN.md; do
    [ -f "$f" ] && DOCS+=("$f")
  done
  # Advisory diagnostic, never a failure and never a warning (see the header).
  for dup_page in README.zh-CN.md "$ZH_TREE/README.md"; do
    [ -f "$dup_page" ] || continue
    CHECKS=$((CHECKS + 1))
    note "$dup_page" "1" "advisory (never fails): SPEC R12/§7.6 禁止语言后缀副本 — a language-suffixed copy of an entry/index page is not part of the frozen layout; derives from the original t4 acceptance criterion, withdrawn by ruling A; absence is confirmed by independent verification (A6)"
  done
fi

# The files this run is responsible for (SPEC A5: an --only run answers only for
# the files it was given).
declare -A TARGET_SET=()
for d in "${DOCS[@]}"; do TARGET_SET["$d"]=1; done

# =============================================================================
# Extraction. One pass per document, emitted as tab-separated records
#   REC <kind> <file> <line> <scope> <payload> <cited-files> <neg>
# kinds: CIT PATH SYM CMD TYPE LINK RETIRED
# Empty fields are written as "-" because `read` collapses adjacent tabs
# (tab is IFS whitespace).
# =============================================================================
extract_doc() {
  awk '
  function up(s) { return toupper(s) }
  # SPEC §7.2: scope names are exactly host|container|device|repo.
  function scope_of(s,    u) {
    u = up(s)
    if (u ~ /(^|[^A-Z])HOST[ \t]*[:(]/) return "host"
    if (u ~ /(^|[^A-Z])CONTAINER[ \t]*[:(]/) return "container"
    if (u ~ /(^|[^A-Z])DEVICE[ \t]*[:(]/) return "device"
    if (u ~ /(^|[^A-Z])REPO[ \t]*[:(]/) return "repo"
    return ""
  }
  # T5: WORKSPACE:/ARTIFACT: are retired. A failure needs an actual use as a
  # path prefix, not a mention of the retired vocabulary in prose.
  function retired_hit(s,    u, res, p, rest, tok, m, key) {
    u = up(s); res = ""
    for (m = 1; m <= 2; m++) {
      key = (m == 1 ? "WORKSPACE:" : "ARTIFACT:")
      p = index(u, key)
      if (p == 0) continue
      rest = substr(s, p + length(key))
      sub(/^[ \t`]+/, "", rest)
      tok = rest; sub(/[^A-Za-z0-9_.\/-].*/, "", tok)
      # only a real path counts: a directory separator, a file extension, or a
      # workspace-root shape — never prose such as "the `ARTIFACT:` tag."
      if (tok ~ /[A-Za-z]/ && (tok ~ /\// || tok ~ /\.[A-Za-z0-9]+$/ || tok ~ /^(env|tmp)/)) {
        res = (res == "" ? key " " tok : res "; " key " " tok)
      }
    }
    # scope=workspace / scope=artifact in a fence info string
    if (s ~ /^[ \t]*```/ && up(s) ~ /SCOPE=(WORKSPACE|ARTIFACT)/) {
      res = (res == "" ? "scope=workspace|artifact" : res "; scope=workspace|artifact")
    }
    return res
  }
  function is_cit(t) { return (t ~ /^[^ \t`]+:[0-9]+(-[0-9]+)?$/) }
  function citpath(t) { p = t; sub(/:[0-9]+(-[0-9]+)?$/, "", p); return p }
  function is_abs(t) { return (substr(t, 1, 1) == "/") }
  function abs_is_fs(t) {
    if (t ~ /^\/(opt|etc|var|home|root|usr|srv|tmp|data|sdcard|system)\/[^\/]/) return 1
    if (gsub(/\//, "/", t) >= 2 && t ~ /\.[A-Za-z0-9]+$/) return 1
    return 0
  }
  function is_pathlike(t,    last, ext, base) {
    if (t ~ /[ \t]/) return 0
    # globs, placeholders and shell metacharacters are patterns, not paths
    if (t ~ /[*?<>|{}[\]$]/) return 0
    if (t ~ /\.\.\./) return 0
    # a repository path never contains a colon; this keeps `host:port/path.apk`
    # URLs and `report:<file>:<line>` provenance out of the path classes
    if (t ~ /:/) return 0
    if (t ~ /^\.\.?\//) return 1
    if (is_abs(t)) return abs_is_fs(t)
    base = t; sub(/.*\//, "", base)
    if (base == "env.sh" || base == "env-container.sh" || base == "env-go.sh") return 1
    if (t ~ /^tmp\//) return 1
    if (t !~ /\//) return 0
    last = t; sub(/.*\//, "", last)
    ext = last; sub(/^.*\./, "", ext)
    if (last == ext) return 0
    if (ext ~ /^(go|kt|kts|cpp|h|cc|hpp|sh|py|service|conf|mjs|md|json|properties|txt|xml|gradle|yml|yaml|toml|so|apk|jar|aar|dex|zip|log|patch|c)$/) return 1
    return 0
  }
  function is_cmd(t) {
    if (t ~ /^:[A-Za-z][A-Za-z0-9_-]*:[A-Za-z][A-Za-z0-9_-]*$/) return 1
    if (t ~ /^--[a-z][a-z0-9-]*$/) return 1
    if (t ~ /^-P[A-Za-z0-9_.]+=[A-Za-z0-9_.-]*$/) return 1
    if (t == "clean" || t == "assembleDebug" || t == "testDebugUnitTest" || t == "compileDebugKotlin") return 1
    return 0
  }
  function is_sym(t) {
    if (t ~ /[ \t\/:]/) return 0
    if (t ~ /\.(sh|py|go|kt|kts|cpp|h|hpp|cc|md|json|txt|xml|conf|service|jar|so|apk|log|mjs|properties|gradle|yml|yaml)$/) return 0
    if (t ~ /^[A-Za-z_][A-Za-z0-9_]*$/ || t ~ /^[A-Za-z_][A-Za-z0-9_]*\.[A-Za-z0-9_]+$/) {
      if (t ~ /_/ || t ~ /[A-Z]/) return 1
    }
    return 0
  }
  function is_typelike(t) { return (t ~ /^[a-z][A-Za-z0-9]*$/) }
  function sc(i) { return (SC[i] == "" ? "-" : SC[i]) }
  { L[FNR] = $0; NL = FNR }
  END {
    infence = 0; fscope = ""; sscope = ""; cursec = 0
    for (i = 1; i <= NL; i++) {
      line = L[i]; IGN[i] = 0; SC[i] = ""; HEAD[i] = 0
      NEG[i] = (line ~ /NEGATIVE EXAMPLE/) ? 1 : 0
      if (line ~ /^[ \t]*```/) {
        IGN[i] = 1
        if (!infence) { infence = 1; fscope = scope_of(line) } else { infence = 0; fscope = "" }
        continue
      }
      if (line ~ /^[ \t]*<!--[ \t]*scope[ \t]*:/) { IGN[i] = 1; sscope = scope_of(line); continue }
      if (line ~ /^#+[ \t]/) { sscope = ""; cursec = i }
      lscope = scope_of(line)
      SC[i] = (lscope != "" ? lscope : (fscope != "" ? fscope : sscope))
      HEAD[i] = cursec
    }
    # citation files per subsection (SPEC V2 allows any file cited in the same
    # subsection), deduplicated
    for (i = 1; i <= NL; i++) {
      if (IGN[i]) continue
      m = split(L[i], parts, "`")
      for (k = 2; k <= m; k += 2) {
        t = parts[k]
        if (t != "" && is_cit(t)) {
          cp = citpath(t)
          sk = HEAD[i] SUBSEP cp
          if (!(sk in SECSEEN)) { SECSEEN[sk] = 1; SECCIT[HEAD[i]] = SECCIT[HEAD[i]] " " cp }
        }
      }
    }
    for (i = 1; i <= NL; i++) {
      if (IGN[i]) continue
      line = L[i]
      r = line
      while ((p = index(r, "](")) > 0) {
        rest = substr(r, p + 2)
        q = index(rest, ")")
        if (q == 0) break
        printf "REC\tLINK\t%s\t%d\t%s\t%s\t-\t%d\n", FILENAME, i, sc(i), substr(rest, 1, q - 1), NEG[i]
        r = substr(rest, q + 1)
      }
      rh = retired_hit(line)
      if (rh != "" && !NEG[i]) {
        printf "REC\tRETIRED\t%s\t%d\t%s\t%s\t-\t%d\n", FILENAME, i, sc(i), rh, NEG[i]
      }
      m = split(line, parts, "`")
      lcits = ""
      for (k = 2; k <= m; k += 2) {
        t = parts[k]
        if (t != "" && is_cit(t)) lcits = (lcits == "" ? citpath(t) : lcits " " citpath(t))
      }
      sect = SECCIT[HEAD[i]]
      sub(/^ +/, "", sect)
      cand = sect
      if (lcits != "") cand = (sect != "" ? sect " " lcits : lcits)
      for (k = 2; k <= m; k += 2) {
        t = parts[k]
        if (t == "") continue
        if (is_cit(t)) { printf "REC\tCIT\t%s\t%d\t%s\t%s\t-\t%d\n", FILENAME, i, sc(i), t, NEG[i]; continue }
        if (is_cmd(t)) { printf "REC\tCMD\t%s\t%d\t%s\t%s\t-\t%d\n", FILENAME, i, sc(i), t, NEG[i]; continue }
        if (is_pathlike(t)) { printf "REC\tPATH\t%s\t%d\t%s\t%s\t-\t%d\n", FILENAME, i, sc(i), t, NEG[i]; continue }
        if (cand != "" && is_sym(t)) { printf "REC\tSYM\t%s\t%d\t%s\t%s\t%s\t%d\n", FILENAME, i, sc(i), t, cand, NEG[i]; continue }
        if (is_typelike(t) && t != "type" && (index(line, "`type`") > 0 || line ~ /SerialName/)) {
          printf "REC\tTYPE\t%s\t%d\t%s\t%s\t-\t%d\n", FILENAME, i, sc(i), t, NEG[i]
        }
      }
    }
  }
  ' "$1"
}

# =============================================================================
# Reference data
# =============================================================================
declare -A PATHCLASS=()
declare -A LINECOUNT=()
declare -A DRIFT_NOTE=()

# Both helpers return through a global: a `$(...)` would run in a subshell and
# discard the cache, making large generated tables quadratic.
PATH_CLASS=""
classify_path() { # <path> -> $PATH_CLASS  (SPEC §7.1: git check-ignore decides)
  local t="$1"
  if [ -n "${PATHCLASS[$t]:-}" ]; then PATH_CLASS="${PATHCLASS[$t]}"; return; fi
  local cls="p1-repo"
  case "$t" in
    "$CONTAINER_ROOT"|"$CONTAINER_ROOT"/*) cls="p4-container" ;;
    /*) cls="p3-host" ;;
    ../*|tmp/*|*"env.sh"|*"env-container.sh"|*"env-go.sh")
      case "$t" in
        "$CONTAINER_ROOT"|"$CONTAINER_ROOT"/*) cls="p4-container" ;;
        /*) cls="p3-host" ;;
        *) cls="p2-workspace" ;;
      esac
      ;;
    *)
      if git check-ignore -q -- "$t" 2>/dev/null; then cls="p5-artifact"; fi
      ;;
  esac
  PATHCLASS[$t]="$cls"
  PATH_CLASS="$cls"
}

LC_TOTAL=0
line_count() { # <file> -> $LC_TOTAL
  local f="$1"
  if [ -n "${LINECOUNT[$f]:-}" ]; then LC_TOTAL="${LINECOUNT[$f]}"; return; fi
  LC_TOTAL=$(awk 'END { print NR }' "$f" 2>/dev/null || echo 0)
  [ -n "$LC_TOTAL" ] || LC_TOTAL=0
  LINECOUNT[$f]="$LC_TOTAL"
}

# SPEC A1: only these extensions are checked by V1.
ext_checked() {
  case "${1##*.}" in
    go|kt|kts|cpp|h|cc|hpp|sh|py|service|conf|mjs|md|json|properties|txt|xml|gradle) return 0 ;;
    *) return 1 ;;
  esac
}

# Generated protocol type set (V3 / C4).
declare -A GEN_TYPES=()
if [ -f "$SIGNALING_TABLE" ]; then
  while IFS= read -r t; do [ -n "$t" ] && GEN_TYPES[$t]=1; done < <(
    awk '/^## 1\. Message types \(Go side\)/{f=1;next} /^## 2\./{f=0} f && /^\| `/{print}' "$SIGNALING_TABLE" |
      sed -E 's/^\| `([^`]+)`.*/\1/'
  )
fi

# Command inventory: live scan of repository scripts (V7 / §7.3 class 1) plus the
# generated inventory (class 2).
declare -A CMDCLASS=()
scan_cmd_file() {
  awk '
  function emit(t) { if (t != "") print t }
  {
    line = $0
    if (line ~ /gradlew|gradle |publish_apk|build_app\.sh/) {
      rest = line
      while (match(rest, /:[A-Za-z][A-Za-z0-9_:]*/)) { emit(substr(rest, RSTART, RLENGTH)); rest = substr(rest, RSTART + RLENGTH) }
      rest = line
      while (match(rest, /-P[A-Za-z0-9_.]+=[A-Za-z0-9_.-]+/)) { emit(substr(rest, RSTART, RLENGTH)); rest = substr(rest, RSTART + RLENGTH) }
      rest = line
      while (match(rest, /--[a-z][a-z0-9-]*/)) { emit(substr(rest, RSTART, RLENGTH)); rest = substr(rest, RSTART + RLENGTH) }
      if (line ~ /(^|[ \t])(clean|assembleDebug|testDebugUnitTest|compileDebugKotlin)([ \t]|$)/) {
        rest = line
        while (match(rest, /(^|[ \t])(clean|assembleDebug|testDebugUnitTest|compileDebugKotlin)([ \t]|$)/)) {
          t = substr(rest, RSTART, RLENGTH); gsub(/^[ \t]+/, "", t); gsub(/[ \t]+$/, "", t)
          emit(t)
          rest = substr(rest, RSTART + RLENGTH)
        }
      }
    }
    if (line ~ /-P[A-Za-z0-9_.]+=/) {
      rest = line
      while (match(rest, /-P[A-Za-z0-9_.]+=[A-Za-z0-9_.-]+/)) { emit(substr(rest, RSTART, RLENGTH)); rest = substr(rest, RSTART + RLENGTH) }
    }
    if (FILENAME ~ /(^|\/)scripts\// && line ~ /^[ \t]*--[a-z][a-z0-9-]*\)/) {
      rest = line
      while (match(rest, /--[a-z][a-z0-9-]*/)) { emit(substr(rest, RSTART, RLENGTH)); rest = substr(rest, RSTART + RLENGTH) }
    }
  }
  ' "$1" 2>/dev/null
}

for f in scripts/*.sh scripts/*.py deploy/*; do
  [ -f "$f" ] || continue
  while IFS= read -r t; do [ -n "$t" ] && CMDCLASS[$t]="in-repo"; done < <(scan_cmd_file "$f")
done
if [ -f "$HOST_CMD_TABLE" ]; then
  while IFS=$'\t' read -r t c; do
    [ -z "$t" ] && continue
    case "${CMDCLASS[$t]:-}" in
      in-repo) ;;
      report) [ "$c" = "in-repo" ] && CMDCLASS[$t]="$c" ;;
      *) CMDCLASS[$t]="$c" ;;
    esac
  done < <(awk '/^## 5\. Machine-readable/{f=1;next} f && /^## /{exit} f && /^\| `/{print}' "$HOST_CMD_TABLE" | sed -E 's/^\| `([^`]+)` \| ([a-z-]+).*/\1\t\2/')
fi

# Documentation vocabulary, not code identifiers (SPEC §6 E4 and tag words).
declare -A VOCAB=(
  [implemented]=1 [partially]=1 [unverified]=1 [rejected]=1 [disproven]=1
  [SPEC]=1 [HOST]=1 [CONTAINER]=1 [DEVICE]=1 [REPO]=1 [host]=1 [container]=1 [device]=1 [repo]=1
  [WORKSPACE]=1 [ARTIFACT]=1 [workspace]=1 [artifact]=1
  [GENERATED]=1 [README]=1 [JSON]=1 [UTF]=1 [SDP]=1 [ICE]=1 [TURN]=1 [STUN]=1
  [APK]=1 [JNI]=1 [UI]=1 [JVM]=1 [ID]=1 [OK]=1 [FAIL]=1 [WARN]=1 [NOTE]=1
  [LINE]=1 [NAME]=1 [FILE]=1 [PATH]=1 [TAG]=1 [TODO]=1 [NN]=1 [X]=1
  [_generated]=1 [FIXME]=1
)

# =============================================================================
# Bilingual layout (SPEC V14/V15)
#
# Frozen layout — captain ruling 2026-09-17 option A, restated in
# doc/design/zh-CN/GLOSSARY.md: the Chinese page is the default entry
# (`README.md`, `doc/design/README.md`), the English original keeps its name
# (`README.en.md`, `doc/design/README.en.md`, `doc/design/NN-*.md`) and the
# translated bodies live in `doc/design/zh-CN/<same name>.md`.
#
# Arming. A pair is judged as soon as one side carries an i18n marker:
#   * the Chinese side exists under doc/design/zh-CN/ (always a new artefact),
#   * or either side already links to its counterpart (the switcher line).
# The root and index Chinese files pre-date this round and stay ordinary
# English documents until they carry the switcher, so their mere existence
# never arms those two pairs; the new README.en.md files do. A full run on the
# pre-i18n tree therefore stays green, and the pair becomes a hard check the
# moment the i18n work lands.
# =============================================================================
PAIRS=()
pair_add() { PAIRS+=("$1|$2|$3"); }   # chinese|english|mode (entry|tree)

# link_records <page> <window> -> "line<TAB>text<TAB>target" for the markdown
# links written on the first <window> lines.
link_records() {
  awk -v n="$2" '
    { if (FNR > n) exit
      s = $0
      while (match(s, /\[[^]]*\]\(/)) {
        text = substr(s, RSTART + 1, RLENGTH - 3)
        rest = substr(s, RSTART + RLENGTH)
        q = index(rest, ")")
        if (q == 0) break
        printf "%d\t%s\t%s\n", FNR, text, substr(rest, 1, q - 1)
        s = substr(rest, q + 1)
      }
    }
  ' "$1" 2>/dev/null
}

# normalize_path <path> -> absolute path, resolved lexically (need not exist)
normalize_path() { realpath -m -- "$1" 2>/dev/null || printf '%s' "$1"; }

# rel_hint <from-page> <target> -> the relative path a link in <from-page> must
# use to reach <target> (repository-relative). Fix hints stay renderable, not
# merely gate-valid: the gate also accepts a repository-root form, but a reader
# (and GitHub) resolves links against the page's own directory.
rel_hint() {
  realpath -m --relative-to="$(dirname "$1")" "$REPO_ROOT/$2" 2>/dev/null || printf '%s' "$2"
}

# switcher_has_extra <line> <zh|en> -> the line carries something besides the
# frozen switcher shape (GLOSSARY §3.1: the switcher stands on its own line).
switcher_has_extra() {
  if [ "$2" = "zh" ]; then
    printf '%s\n' "$1" | sed -e 's/^[[:space:]]*>[[:space:]]*//' -e 's/\*\*//g' \
      -e 's/\[English\]([^)]*)//' -e 's/中文（默认）//g' -e 's/·//g' -e 's/[[:space:]]//g' | grep -q .
  else
    printf '%s\n' "$1" | sed -e 's/^[[:space:]]*>[[:space:]]*//' -e 's/\*\*//g' \
      -e 's/\[中文（默认）\]([^)]*)//' -e 's/English//g' -e 's/·//g' -e 's/[[:space:]]//g' | grep -q .
  fi
}

# fullwidth_hint <page> -> SPEC §7.6 note when the page writes the full-width
# bracket form, which renders no link and therefore fails V14.
fullwidth_hint() {
  if head -n "$SWITCHER_WINDOW" -- "$1" 2>/dev/null | grep -qE '\[(English|中文（默认）)\]（'; then
    printf ' (a full-width bracket form is present but renders no link; a real page writes half-width parentheses, SPEC §7.6)'
  fi
}

# switcher_ln <page> <text-pattern> -> line number of the first matching link
switcher_ln() {
  local page="$1" pattern="$2" ln text target
  if [ -f "$page" ]; then
    while IFS=$'\t' read -r ln text target; do
      case "$text" in $pattern) printf '%d\n' "$ln"; return 0 ;; esac
    done < <(link_records "$page" "$SWITCHER_WINDOW")
  fi
  printf '1\n'
}

# has_switcher <page> <text-pattern> <counterpart> -> the page carries a link
# whose text matches <text-pattern> and whose target resolves to <counterpart>
# (repository-relative), within the first $SWITCHER_WINDOW lines.
has_switcher() {
  local page="$1" pattern="$2" counterpart="$3" dir want ln text target
  [ -f "$page" ] || return 1
  dir=$(dirname "$page")
  want=$(normalize_path "$REPO_ROOT/$counterpart")
  while IFS=$'\t' read -r ln text target; do
    [ -n "${target:-}" ] || continue
    case "$text" in $pattern) : ;; *) continue ;; esac
    target="${target%%#*}"
    target="${target#"${target%%[![:space:]]*}"}"
    target="${target%"${target##*[![:space:]]}"}"
    target="${target#<}"; target="${target%>}"
    [ -n "$target" ] || continue
    case "$target" in http://*|https://*|mailto:*|/*) continue ;; esac
    if [ "$(normalize_path "$dir/$target")" = "$want" ] \
    || [ "$(normalize_path "$REPO_ROOT/$target")" = "$want" ]; then
      return 0
    fi
  done < <(link_records "$page" "$SWITCHER_WINDOW")
  return 1
}

# in_target <file> -> the file belongs to this run's target set
in_target() { [ -n "${TARGET_SET[$1]:-}" ]; }

# pair_check <chinese page> <english page> <mode>
pair_check() {
  local zh="$1" en="$2" mode="$3" armed=0 scope_all=1 ln zh_line en_line
  [ -f "$zh" ] || [ -f "$en" ] || return 0
  case "$zh" in "$ZH_TREE"/*) [ -f "$zh" ] && armed=1 ;; esac
  [ "$mode" = "entry" ] && [ -f "$en" ] && armed=1
  if [ "$armed" = 0 ] && [ -f "$zh" ] && has_switcher "$zh" 'English' "$en"; then armed=1; fi
  if [ "$armed" = 0 ] && [ -f "$en" ] && has_switcher "$en" '中文（默认）' "$zh"; then armed=1; fi
  [ "$armed" = 1 ] || return 0
  [ "${#ONLY[@]}" -eq 0 ] || scope_all=0

  # --- Chinese-default side ------------------------------------------------
  if [ "$scope_all" = 1 ] || in_target "$zh"; then
    if [ ! -f "$zh" ]; then
      fail "$en" "$(switcher_ln "$en" '中文*')" \
        "missing language switcher: \`$en\` advertises the Chinese counterpart \`$zh\`, which does not exist" \
        "create \`$zh\` with \`> **中文（默认）** · [English]($(rel_hint "$zh" "$en"))\` (SPEC V14; 补上切换器行或修正链接目标)"
    else
      CHECKS=$((CHECKS + 1))
      if has_switcher "$zh" 'English' "$en"; then
        ln=$(switcher_ln "$zh" 'English')
        zh_line=$(sed -n "${ln}p" "$zh")
        case "$zh_line" in
          *中文（默认）*) : ;;
          *)
            fail "$zh" "$ln" "missing language switcher: the switcher line for \`$en\` does not carry the frozen \`中文（默认）\` label" \
              "write \`> **中文（默认）** · [English]($(rel_hint "$zh" "$en"))\` (SPEC V14; GLOSSARY §3.2)"
            ;;
        esac
        if switcher_has_extra "$zh_line" zh; then
          warn "$zh" "$ln" "switcher line carries extra content; GLOSSARY §3.1 requires the switcher to stand on a line of its own (SPEC V14)"
        fi
      else
        fail "$zh" "1" "missing language switcher: no \`[English](…)\` link to \`$en\` in the first $SWITCHER_WINDOW lines$(fullwidth_hint "$zh")" \
          "add \`> **中文（默认）** · [English]($(rel_hint "$zh" "$en"))\` (SPEC V14; 补上切换器行或修正链接目标)"
      fi
    fi
  fi

  # --- English side --------------------------------------------------------
  if [ "$scope_all" = 1 ] || in_target "$en"; then
    if [ ! -f "$en" ]; then
      fail "$zh" "1" "missing language switcher: \`$zh\` is the Chinese default but its English counterpart \`$en\` does not exist" \
        "create \`$en\` from the English original and add \`> [中文（默认）]($(rel_hint "$en" "$zh")) · English\` (SPEC V14; 补上切换器行或修正链接目标)"
    else
      CHECKS=$((CHECKS + 1))
      if has_switcher "$en" '中文（默认）' "$zh"; then
        ln=$(switcher_ln "$en" '中文（默认）')
        en_line=$(sed -n "${ln}p" "$en")
        case "$en_line" in
          *English*) : ;;
          *)
            fail "$en" "$ln" "missing language switcher: the switcher line for \`$zh\` does not end with the frozen \`English\` marker" \
              "write \`> [中文（默认）]($(rel_hint "$en" "$zh")) · English\` (SPEC V14; GLOSSARY §3.3)"
            ;;
        esac
        if switcher_has_extra "$en_line" en; then
          warn "$en" "$ln" "switcher line carries extra content; GLOSSARY §3.1 requires the switcher to stand on a line of its own (SPEC V14)"
        fi
      else
        fail "$en" "1" "missing language switcher: no \`[中文（默认）](…)\` link to \`$zh\` in the first $SWITCHER_WINDOW lines$(fullwidth_hint "$en")" \
          "add \`> [中文（默认）]($(rel_hint "$en" "$zh")) · English\` (SPEC V14; 补上切换器行或修正链接目标)"
      fi
    fi
  fi
}

# Frozen pair list (ruling A §3): the two default entry points plus the
# translated bodies. A Chinese page without an English counterpart
# (zh-CN/GLOSSARY.md, zh-CN/SPEC-guide.md) is exempt; any other zh-CN page is
# reported so the frozen layout cannot drift silently.
pair_add "README.md" "README.en.md" "entry"
pair_add "doc/design/README.md" "doc/design/README.en.md" "entry"
if [ -d "$ZH_TREE" ]; then
  while IFS= read -r zf; do
    base=$(basename "$zf")
    case "$base" in
      # GLOSSARY §3.4 / SPEC §7.6: the pairing list is exhaustive — NN = 01-05.
      0[1-5]-*.md) pair_add "$zf" "doc/design/$base" "tree" ;;
      GLOSSARY.md|SPEC-guide.md) : ;;
      # The entry/index duplicate is covered by the advisory NOTE below, not by
      # this warning (one diagnostic per condition, and it never fails).
      README.md) : ;;
      *) warn "$zf" "1" "Chinese page is not in the frozen V14 pair list (doc/design/zh-CN/GLOSSARY.md §2.1/L4); pairing skipped — remove it, or record it in the freeze manifest and the SPEC (SPEC V14/R8)" ;;
    esac
  done < <(find "$ZH_TREE" -maxdepth 1 -name '*.md' -type f | sort)
fi

# =============================================================================
# Per-document checks
# =============================================================================
for doc in "${DOCS[@]}"; do
  [ -f "$doc" ] || continue

  while IFS=$'\t' read -r rec kind file line scope payload cits neg; do
    [ "$rec" = "REC" ] || continue
    [ "$scope" = "-" ] && scope=""
    [ "$cits" = "-" ] && cits=""
    case "$kind" in
      CIT)
        CHECKS=$((CHECKS + 1))
        path="${payload%:*}"
        rng="${payload##*:}"
        case "$path" in report:*) path="${path#report:}" ;; esac
        if [ "${path#/}" != "$path" ]; then
          # SPEC A1: off-repository citation -> warning, never a failure
          warn "$file" "$line" "citation target is outside the repository and cannot be opened: \`$payload\`"
          continue
        fi
        if [ "${path#../}" != "$path" ]; then
          while [ "${path#../}" != "$path" ]; do path="${path#../}"; done
          path="$WORKSPACE_ROOT/$path"
        fi
        ext_checked "$path" || continue
        if [ ! -e "$path" ]; then
          fail "$file" "$line" "cited file does not exist: \`$path\`" \
            "fix the path or remove the citation (SPEC V1)"
          continue
        fi
        [ -d "$path" ] && { fail "$file" "$line" "cited path is a directory: \`$path\`" "cite a file (SPEC V1)"; continue; }
        line_count "$path"; total="$LC_TOTAL"
        lo="${rng%-*}"; hi="${rng##*-}"
        if [ "$lo" -lt 1 ] || [ "$lo" -gt "$total" ]; then
          fail "$file" "$line" "\`$path\` has $total lines but the citation points at line $lo" \
            "update the line number or the range (SPEC C8/A11)"
        elif [ "$hi" -gt "$total" ]; then
          fail "$file" "$line" "citation range end $hi exceeds \`$path\` ($total lines)" \
            "update the range (SPEC A2)"
        fi
        # Captain D③: a report citation is historical evidence — its existence is
        # checked, but its line numbers must never be reused as source pointers.
        # Reported once per document per report file to stay readable.
        case "$path" in
          reports/*)
            if [ -z "${DRIFT_NOTE["$file|$path"]:-}" ]; then
              DRIFT_NOTE["$file|$path"]=1
              note "$file" "$line" "historical evidence, may drift: \`$path\` (report line numbers are not source pointers)"
            fi
            ;;
        esac
        ;;
      RETIRED)
        # Captain's frozen vocabulary (2026-09-17; normative text in SPEC T5, V8,
        # §7.4 and the §7.2 note): WORKSPACE:/ARTIFACT: are not scope tokens.
        # Writing either one is a writing violation regardless of how the path it
        # names would be classified — auto-classified (P2/P5) or version-controlled
        # (P1). Only a line explicitly marked NEGATIVE EXAMPLE is exempt (SPEC §8 R11).
        CHECKS=$((CHECKS + 1))
        marker="${payload%% *}"
        mpath="${payload#* }"
        if [ "$neg" = "1" ]; then
          note "$file" "$line" "retired marker exempted as a NEGATIVE EXAMPLE: $payload"
        elif [ "$mpath" != "$payload" ] && [ -n "$mpath" ] && [ "$mpath" != "-" ]; then
          fail "$file" "$line" "retired marker used as a path prefix: $marker $mpath" \
            "drop the prefix — P2/P5 are auto-classified, markers are retired (SPEC T5/V8)"
        else
          fail "$file" "$line" "retired marker used in a scope notation: $payload" \
            "keep only HOST/CONTAINER/DEVICE/REPO notation (SPEC T5/§7.2)"
        fi
        ;;
      PATH)
        CHECKS=$((CHECKS + 1))
        classify_path "$payload"; cls="$PATH_CLASS"
        case "$cls" in
          p4-container)
            if [ ! -e "$payload" ]; then
              fail "$file" "$line" "missing container path: \`$payload\` (P4 is hard-checked)" \
                "fix the path (SPEC §7.1 P4)"
            fi
            ;;
          p3-host)
            case "$scope" in
              host|device) note "$file" "$line" "UNVERIFIED (host-only path): \`$payload\`" ;;
              *)
                if [ "$neg" != "1" ]; then
                  fail "$file" "$line" "unmarked host path: \`$payload\`" \
                    "mark it \`HOST: $payload\`, or \`# HOST (evidence)\`, or a \`scope=host\` fence (SPEC T1/V11)"
                fi
                ;;
            esac
            ;;
          p2-workspace)
            rp="$payload"
            while [ "${rp#../}" != "$rp" ]; do rp="${rp#../}"; done
            if [ ! -e "$WORKSPACE_ROOT/$rp" ]; then
              fail "$file" "$line" "missing workspace path: \`$payload\` (P2 is hard-checked against the workspace root)" \
                "fix the path (workspace root = $WORKSPACE_ROOT)"
            fi
            ;;
          p5-artifact)
            if [ ! -e "$payload" ]; then
              note "$file" "$line" "UNVERIFIED (build output, gitignored): \`$payload\`"
              # Captain ruling C: `git check-ignore` also reports IGNORED for
              # non-existent paths, so an absent ignored path is flagged unless
              # the line itself marks it as an artifact. Warning only (A6).
              if ! sed -n "${line}p" "$file" 2>/dev/null | grep -qiE 'ARTIFACT[ \t]*:'; then
                warn "$file" "$line" "typo-suspect (gitignored path absent): \`$payload\`"
              fi
            fi
            ;;
          *)
            if [ ! -e "$payload" ]; then
              fail "$file" "$line" "missing repo path: \`$payload\`" \
                "fix the path or remove the backticks (SPEC §7.1 P1)"
            fi
            ;;
        esac
        ;;
      CMD)
        CHECKS=$((CHECKS + 1))
        cls="${CMDCLASS[$payload]:-}"
        case "$cls" in
          in-repo) : ;;
          "")
            fail "$file" "$line" "\`$payload\` is neither in repository scripts nor in $HOST_CMD_TABLE" \
              "run scripts/gen-doc-tables.sh, or correct the task/flag name (SPEC V7)"
            ;;
          *)
            # SPEC §7.3: citing a host- or workspace-only command name requires
            # in-repository evidence in the same statement, in either form:
            # an inline `reports/<file>:<line>` or a host-commands.md reference.
            # The generated inventory itself is the evidence source, so it is
            # exempt (its rows carry the provenance).
            case "$doc" in "$GENERATED_DIR"/*) continue ;; esac
            ok_ev=0
            if sed -n "${line}p" "$file" 2>/dev/null | grep -qE 'reports/|host-commands\.md'; then
              ok_ev=1
            else
              sec_start=$(awk -v n="$line" 'NR<=n && /^#{1,6} /{s=NR} END{print (s?s:1)}' "$file")
              if sed -n "${sec_start},${line}p" "$file" | grep -qE 'reports/[A-Za-z0-9._-]+|host-commands\.md'; then ok_ev=1; fi
            fi
            if [ "$ok_ev" != "1" ]; then
              fail "$file" "$line" "\`$payload\` is host/workspace-only and is cited without in-repository evidence" \
                "add \`reports/<file>:<line>\` or refer to the $HOST_CMD_TABLE entry (SPEC §7.3)"
            fi
            ;;
        esac
        ;;
      SYM)
        CHECKS=$((CHECKS + 1))
        [ -n "${VOCAB[$payload]:-}" ] && continue
        if ! read -r -a cfiles <<<"$cits" || [ "${#cfiles[@]}" -eq 0 ]; then continue; fi
        if ! grep -lFq -- "$payload" "${cfiles[@]}" 2>/dev/null; then
          fail "$file" "$line" "symbol \`$payload\` is not present in the cited file(s) ($cits)" \
            "use the exact source spelling or cite the file that defines it (SPEC V2/C3)"
        fi
        ;;
      TYPE)
        CHECKS=$((CHECKS + 1))
        if [ -f "$SIGNALING_TABLE" ]; then
          [ -n "${GEN_TYPES[$payload]:-}" ] || fail "$file" "$line" "message type \`$payload\` is not in the generated signalling table" \
            "use a type from $SIGNALING_TABLE or regenerate the table (SPEC V3/C4)"
        fi
        ;;
      LINK) : ;;
    esac
  done < <(extract_doc "$doc")

  # V4: relative markdown links resolve on disk
  while IFS=$'\t' read -r rec kind file line scope target extra neg; do
    [ "$rec" = "REC" ] || continue
    case "$target" in
      http://*|https://*|mailto:*|\#*|"") continue ;;
    esac
    target="${target%%#*}"
    [ -z "$target" ] && continue
    case "$target" in /*) continue ;; esac
    CHECKS=$((CHECKS + 1))
    dir=$(dirname "$file")
    if [ ! -e "$dir/$target" ] && [ ! -e "$REPO_ROOT/$target" ]; then
      fail "$file" "$line" "relative link target does not resolve: \`$target\`" \
        "fix the link path (SPEC C7/V4)"
    fi
  done < <(extract_doc "$doc" | awk -F'\t' '$2=="LINK"{print}')
done

# V14: the bilingual switcher, in both directions, for the frozen pair list.
for p in "${PAIRS[@]}"; do
  p_zh="${p%%|*}"; p_rest="${p#*|}"; p_en="${p_rest%%|*}"; p_mode="${p_rest##*|}"
  pair_check "$p_zh" "$p_en" "$p_mode"
done

# V15: the translation disclaimer on every translated page.
for f in "${DOCS[@]}"; do
  case "$f" in
    "$ZH_TREE"/SPEC-guide.md|"$ZH_TREE"/0[1-5]-*.md) : ;;
    *) continue ;;
  esac
  [ -f "$f" ] || continue
  CHECKS=$((CHECKS + 1))
  ln=$(head -n "$SWITCHER_WINDOW" -- "$f" | grep -nF -- "$DISCLAIMER" | head -1 | cut -d: -f1)
  if [ -z "$ln" ]; then
    fail "$f" "1" "missing translation disclaimer: the first $SWITCHER_WINDOW lines do not state \`$DISCLAIMER\`" \
      "add \`> $DISCLAIMER\` (SPEC V15)"
  elif ! sed -n "${ln}p" "$f" | grep -q '^[[:space:]]*>'; then
    warn "$f" "$ln" "translation disclaimer is not a blockquote line; the frozen template writes it as \`> $DISCLAIMER\` (SPEC V15)"
  fi
done

# V3 completeness: every generated message type must appear in every protocol
# document of this run — the English original and, once it exists, its Chinese
# translation (protocol type names are never translated). Keeping only the last
# match would silently drop the English check as soon as zh-CN/05-*.md joins
# the target set.
PROTO_DOCS=()
for d in "${DOCS[@]}"; do
  [ -f "$d" ] || continue
  case "$(basename "$d")" in *protocol*) PROTO_DOCS+=("$d") ;; esac
done
if [ "${#PROTO_DOCS[@]}" -gt 0 ] && [ -f "$SIGNALING_TABLE" ]; then
  for pd in "${PROTO_DOCS[@]}"; do
    pd_fix="document it or regenerate $SIGNALING_TABLE (SPEC V3)"
    case "$pd" in "$ZH_TREE"/*) pd_fix="add the type name verbatim — protocol type names are not translated (SPEC V3)" ;; esac
    for t in "${!GEN_TYPES[@]}"; do
      CHECKS=$((CHECKS + 1))
      if ! grep -qF "\`$t\`" "$pd"; then
        fail "$pd" "1" "generated message type \`$t\` is not mentioned in this protocol document" \
          "$pd_fix"
      fi
    done
  done
elif [ "${#PROTO_DOCS[@]}" -gt 0 ]; then
  for pd in "${PROTO_DOCS[@]}"; do
    warn "$pd" "1" "$SIGNALING_TABLE is missing; V3 skipped (run scripts/gen-doc-tables.sh)"
  done
fi

# V5: legacy stubs (doc/*.md and doc/adr/*.md), archive mirrors the structure
STUBS=()
if [ "${#ONLY[@]}" -eq 0 ]; then
  while IFS= read -r s; do STUBS+=("$s"); done < <(find doc -maxdepth 1 -name '*.md' -type f ! -name 'README.md' | sort)
  while IFS= read -r s; do STUBS+=("$s"); done < <(find doc/adr -maxdepth 1 -name '*.md' -type f ! -name 'README.md' 2>/dev/null | sort)
else
  for p in "${ONLY[@]}"; do
    [ -f "$p" ] || continue
    case "$(dirname "$p")" in
      doc) [ "$(basename "$p")" != "README.md" ] && STUBS+=("$p") ;;
      doc/adr) [ "$(basename "$p")" != "README.md" ] && STUBS+=("$p") ;;
    esac
  done
fi
for s in "${STUBS[@]:-}"; do
  [ -n "$s" ] || continue
  CHECKS=$((CHECKS + 1))
  rel="${s#doc/}"
  n=$(grep -cve '^[[:space:]]*$' "$s")
  if [ "$n" != "1" ]; then
    fail "$s" "1" "legacy stub must be exactly one non-empty line, found $n" \
      "leave a single line naming doc/archive/$rel (SPEC R3/V5)"
    continue
  fi
  if ! grep -q "doc/archive/$rel" "$s"; then
    fail "$s" "1" "legacy stub does not name doc/archive/$rel" "point the stub at its archived file (SPEC R3/V5)"
  elif [ ! -f "doc/archive/$rel" ]; then
    fail "$s" "1" "legacy stub points at doc/archive/$rel which does not exist" "restore the archive file (SPEC V5)"
  fi
done

# V6: directory conventions and generated headers
CHECKS=$((CHECKS + 1))
if [ ! -L docs ]; then
  fail "docs" "1" "\`docs\` is not a symlink" "recreate it as a symlink to doc/design (SPEC R1/V6)"
elif [ "$(readlink docs)" != "doc/design" ]; then
  fail "docs" "1" "\`docs\` points at $(readlink docs), expected doc/design" "fix the symlink target (SPEC R1/V6)"
fi
if [ -d "$GENERATED_DIR" ]; then
  for g in "$GENERATED_DIR"/*.md; do
    [ -f "$g" ] || continue
    CHECKS=$((CHECKS + 1))
    if ! head -5 "$g" | grep -q 'GENERATED — do not edit'; then
      fail "$g" "1" "generated file lacks the 'GENERATED — do not edit' header" \
        "regenerate with bash scripts/gen-doc-tables.sh (SPEC V6/R5)"
    fi
  done
else
  warn "$GENERATED_DIR" "1" "generated table directory is missing; run scripts/gen-doc-tables.sh"
fi

# =============================================================================
printf '\n'
if [ "$FAILURES" -eq 0 ]; then
  printf 'doc-verify.sh: PASS (%d checks, %d warnings)\n' "$CHECKS" "$WARNINGS"
  exit 0
else
  printf 'doc-verify.sh: FAIL (%d failures, %d warnings, %d checks)\n' "$FAILURES" "$WARNINGS" "$CHECKS"
  exit 1
fi
