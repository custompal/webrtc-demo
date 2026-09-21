#!/usr/bin/env bash
# =============================================================================
# i18n-audit.sh — reproducible zh-CN/EN bilingual consistency audit.
#
# This is an INDEPENDENT supplementary audit. It is NOT the delivery gate: the
# gate is `scripts/doc-verify.sh` (SPEC §9) and it stays the only delivery
# criterion. This script freezes, as one re-runnable command, the manual checks
# that reports/61-i18n-verification.md §2–§3 performed by hand. When the two
# disagree, the gate wins and the divergence is reported to the captain.
#
# Frozen criteria (reports/64-followup-requirements.md §2 T1; pair/declaration
# enumeration extended to NN = 01–11 by reports/66-followup2-requirements.md
# §3.4.3, with the existence-armed counting rule ruled by the captain
# (message b1c180c8-f28f-41b4-8987-5ea7bda15dcd, restated in
# cd5f2e73-e0cd-436d-9895-2360d836f7b2):
#   Z1 PAIR          bilingual switcher, both directions, exact frozen shape,
#                    half-width parentheses, target resolves to the counterpart
#   Z2 DISCLAIMER    the verbatim translation disclaimer in every §4 Y-1 file
#                    that exists on disk: exactly once in the first 8 lines, and
#                    one non-fenced whole-line occurrence per existing Y-1 page
#                    repo-wide (expected count = |Y-1 ∩ disk|; 12 once all 12
#                    registered pages exist). Existence-armed per reports/66
#                    §3.4.3 + captain ruling: pages that are not on disk yet are
#                    not reported as failures
#   Z3 TERMS-TABLE   GLOSSARY §6 is 98 data rows x 4 non-empty cells, # = 1..98
#   Z4 TERMS-STATUS  GLOSSARY §5.5 status words: the English original inside
#                    full-width parentheses is preceded by its frozen Chinese
#                    translation (0 deviations; baseline 44 hits over the whole
#                    zh-CN tree, including the §5.5 example line). A status word
#                    written in Chinese prose must also use full-width
#                    parentheses — that is what makes probe P5 (reports/64 §2.4)
#                    fail; occurrences inside inline code spans are exempt,
#                    because there they are code content (e.g. the code span
#                    `unverified (host script)` in zh-CN/01-requirements.md)
#   Z5 TERMS-DENY    §6.1 narrow denylist: 座位 / 会议室 absent from the 12
#                    translated pages; §6 rows whose 首现说明 says 状态词 take
#                    their 中文定译 from the §5.5 frozen forms
#   Z6 CITATIONS     11 body pairs: deduplicated `path:LINE` / `path:L1-L2`
#                    citation sets are equal, every target exists, every line
#                    (or range endpoint) is inside the target file
#   Z7 CODESPAN      11 body pairs: inline code-span MULTISETS are equal
#   Z8 STRUCTURE     11 body pairs: h2 / h3 / table data rows / fenced code
#                    blocks are equal
#   Z9 LAYOUT        no language-suffixed copy of an entry/index page; no
#                    legacy `语言 / Language:` switcher left in the doc set
#
# Existence-armed enumeration (reports/66 §3.4.3 + captain rulings b1 and b2):
# Z1, Z2 and the Z6/Z7/Z8 pair set are registered for the full frozen 13-pair
# (2 entry + 11 body) and 12 Y-1 list (NN = 01–11), but a registered pair is
# judged only once it is armed. Arming (the audit-side mirror of the gate's V14
# `has_switcher` rule) is: the Chinese page exists under doc/design/zh-CN/, or
# either side already carries its frozen switcher line, or — for the two entry
# pairs only — the English side exists. An armed pair whose counterpart is
# absent is a FAIL[Z1]. A registered pair that is not armed is skipped and
# reported only in the Z1 NOTE line; the delivery state must leave no
# registered pair unarmed (armed pairs = 13, armed body pairs = 11, skipped =
# 0). Z2's expected count is |Y-1 ∩ disk|, which equals 12 once every
# registered page exists. This keeps full gate strength for every page that
# exists while a partially landed 01–11 set is never reported as a failure for
# pages that do not exist yet.
#
# Normative sources: doc/design/SPEC.md §7.5/§7.6/§9, GLOSSARY.md v1.1.1+
# §3/§4/§5.3/§5.4/§5.5/§6/§6.1, reports/61 §2–§3, reports/64 §2, reports/66
# §3.4.3.
#
# Usage
#   bash scripts/i18n-audit.sh                    # full audit
#   bash scripts/i18n-audit.sh --only <path>      # only the given file's own
#                                                 # obligations (repeatable, and
#                                                 # several paths after one flag)
#   bash scripts/i18n-audit.sh --repo-mode        # repository-only audit (CI /
#                                                 # bare clone); see below
#   bash scripts/i18n-audit.sh -h | --help        # print this header, exit 0
#
# `--repo-mode` is the audit-side mirror of the gate's flag of the same name
# (captain governance item 11). A faithful bare clone has no surrounding
# workspace, so Z6 cannot satisfy the reference classes that depend on it. In
# this mode exactly those classes are downgraded to per-record, class-tagged
# NOTEs plus a machine-readable summary line, and `exit 0` then means "no
# failure outside those classes". The class set is the gate's four class names
# with the gate's own predicates: P2 (a citation payload of the gate's
# p2-workspace shape — `../`-prefix, `tmp/`-prefix, or an env-script basename —
# whose WORKSPACE_ROOT target is missing), ENVSLASH (`(../)+env[-go|-container]
# .sh:LINE`), SUBMODULE (target under a gitlink directory read at run time from
# `git ls-files -s`), P4 (target inside the container root). Nothing else is
# downgraded: in both modes a genuinely missing in-repo path, an out-of-bounds
# line, a missing switcher, a missing disclaimer, a Z7 code-span mismatch and a
# Z8 structure mismatch all stay FAIL.
#
# `--only` follows the gate's SPEC A5 reading (reports/64 §2.3): a file answers
# only for its own obligations. Pair-equality checks (Z6/Z7/Z8) still READ the
# counterpart, but a failure is attributed to the listed file. Per file:
#   * zh body page 01–11 : Z1 (own switcher), Z2, Z4, Z5(a), Z6/Z7/Z8 of its pair
#   * zh-CN/SPEC-guide.md: Z2, Z5(a)      (Z1 exempt per GLOSSARY §3.4 S4)
#   * zh-CN/GLOSSARY.md  : Z3, Z4         (Z1/Z2 exempt; §2.3)
#   * the two READMEs and the EN pages: Z1 (own switcher), their pair's
#     Z6/Z7/Z8, and their own Z9(b) text
#   * anything else      : own Z9(b) text only
# Repo-wide invariants (Z2 uniqueness count, Z9(a) forbidden copies) run in
# full mode only: they are not a single file's own obligation. Z9(b) — the
# legacy `语言 / Language:` switcher text — is checked on every listed file that
# belongs to the doc set, because it is that file's own text.
#
# Exit code (frozen): 0 = no FAIL, 1 = at least one FAIL, 2 = invocation or
# environment error (unknown argument, missing --only target, missing
# GLOSSARY.md). WARN/NOTE never change the exit code.
#
# Finding format:
#   file:line → FAIL[Z<id>] <problem> → <suggested fix>
#   WARN file:line → <text>     NOTE file:line → <text>
# Findings are sorted by Z-id, path and line; the whole stdout is byte-identical
# across runs on the same tree.
#
# Reads only: never writes inside the repository, never runs Gradle, never
# commits. Temporary files live under mktemp -d and are removed on EXIT and on
# INT/TERM/HUP (cleanup is re-entrant, so a signal trap followed by the EXIT
# trap is safe). SIGKILL (9) is NOT catchable and no trap can cover it: a
# `kill -9`, an OOM kill, or a `timeout --kill-after` fallback still leaves the
# i18n-audit.XXXXXX directory behind (empty only if the kill lands in the short
# window before the first record is written). That is an irreducible known
# boundary — this script does not claim to leave nothing behind under all
# circumstances.
# ==== end of header ====
set -uo pipefail

LC_ALL=C
export LC_ALL

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$REPO_ROOT"

# The gate resolves workspace-root references against WORKSPACE_ROOT
# (doc-verify.sh line 80); Z6 must use the same root or the two scripts
# disagree on the same citation (captain revision b2).
WORKSPACE_ROOT=$(cd "$REPO_ROOT/../.." && pwd)

ZH_TREE="doc/design/zh-CN"
GLOSSARY="$ZH_TREE/GLOSSARY.md"
SPEC_GUIDE="$ZH_TREE/SPEC-guide.md"
SWITCHER_WINDOW=8
DISCLAIMER='> 译文：若与英文原文冲突，以英文原文为准。'

# The 13 frozen pairs (reports/64 §2.2, extended to NN = 01–11 by reports/66
# §3.4.3). Indexes 0..1 are the entry/index pairs, 2..12 are the eleven body
# pairs that Z6/Z7/Z8 cover. Z1/Z6/Z7/Z8 are existence-armed (see the header):
# arming follows the V14 has_switcher rule, and an armed pair whose counterpart
# is absent is a FAIL[Z1]; an unarmed registered pair is skipped.
PAIR_ZH=(
  "README.md"
  "doc/design/README.md"
  "doc/design/zh-CN/01-requirements.md"
  "doc/design/zh-CN/02-architecture.md"
  "doc/design/zh-CN/03-app-architecture.md"
  "doc/design/zh-CN/04-signaling-service.md"
  "doc/design/zh-CN/05-protocols.md"
  "doc/design/zh-CN/06-flows.md"
  "doc/design/zh-CN/07-build-and-deploy.md"
  "doc/design/zh-CN/08-issues-and-solutions.md"
  "doc/design/zh-CN/09-verification-and-limitations.md"
  "doc/design/zh-CN/10-code-map.md"
  "doc/design/zh-CN/11-coding-standards.md"
)
PAIR_EN=(
  "README.en.md"
  "doc/design/README.en.md"
  "doc/design/01-requirements.md"
  "doc/design/02-architecture.md"
  "doc/design/03-app-architecture.md"
  "doc/design/04-signaling-service.md"
  "doc/design/05-protocols.md"
  "doc/design/06-flows.md"
  "doc/design/07-build-and-deploy.md"
  "doc/design/08-issues-and-solutions.md"
  "doc/design/09-verification-and-limitations.md"
  "doc/design/10-code-map.md"
  "doc/design/11-coding-standards.md"
)
BODY_PAIRS=(2 3 4 5 6 7 8 9 10 11 12)
Y1=(
  "doc/design/zh-CN/01-requirements.md"
  "doc/design/zh-CN/02-architecture.md"
  "doc/design/zh-CN/03-app-architecture.md"
  "doc/design/zh-CN/04-signaling-service.md"
  "doc/design/zh-CN/05-protocols.md"
  "doc/design/zh-CN/06-flows.md"
  "doc/design/zh-CN/07-build-and-deploy.md"
  "doc/design/zh-CN/08-issues-and-solutions.md"
  "doc/design/zh-CN/09-verification-and-limitations.md"
  "doc/design/zh-CN/10-code-map.md"
  "doc/design/zh-CN/11-coding-standards.md"
  "doc/design/zh-CN/SPEC-guide.md"
)
FORBIDDEN_COPIES=("README.zh-CN.md" "$ZH_TREE/README.md")
LEGACY_SWITCHER='语言 / Language:'
STATUS_ZH=("已实现" "部分实现" "已知限制" "未验证" "已否决" "已证伪")

usage() { sed -n '2,/^# ==== end of header ====$/p' "${BASH_SOURCE[0]}"; }

# -----------------------------------------------------------------------------
# Arguments
# -----------------------------------------------------------------------------
ONLY=()
REPO_MODE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --only)
      shift
      while [ $# -gt 0 ] && [ "${1#--}" = "$1" ]; do ONLY+=("$1"); shift; done
      ;;
    --only=*) ONLY+=("${1#--only=}"); shift ;;
    --repo-mode) REPO_MODE=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) printf 'i18n-audit.sh: unknown argument: %s\n' "$1" >&2; exit 2 ;;
  esac
done

if [ ! -f "$GLOSSARY" ]; then
  printf 'i18n-audit.sh: missing required normative source: %s\n' "$GLOSSARY" >&2
  exit 2
fi

AUDIT_TMP=$(mktemp -d "${TMPDIR:-/tmp}/i18n-audit.XXXXXX") || {
  printf 'i18n-audit.sh: cannot create a temporary directory\n' >&2
  exit 2
}
# Cleanup covers all four catchable exits: EXIT plus INT/TERM/HUP. Each signal
# exit is 128+signum (INT=130, TERM=143, HUP=129) so a killed CI run can never
# be mistaken for a pass. cleanup() is idempotent and re-entrant: the signal
# trap runs it and then `exit`, which fires the EXIT trap again; the empty-value
# guard keeps `rm -rf` off an empty string, and the trailing assignment leaves
# the function status 0 even when the directory is already gone, so the exit
# code is never polluted. SIGKILL is uncatchable and stays an open boundary
# (see the header).
cleanup() { [ -n "${AUDIT_TMP:-}" ] && rm -rf -- "$AUDIT_TMP"; AUDIT_TMP=""; }
trap cleanup EXIT
trap 'cleanup; exit 130' INT
trap 'cleanup; exit 143' TERM
trap 'cleanup; exit 129' HUP

# -----------------------------------------------------------------------------
# --repo-mode (audit-side mirror of the gate's repository-only mode; captain
# governance item 11). Only Z6's citation-existence failures can be attributed
# to a missing surrounding workspace, so only they consult this block. The class
# names and predicates are the gate's; default mode never enters here, so its
# product surface stays byte-identical.
# -----------------------------------------------------------------------------
CONTAINER_ROOT="/data/dsh/home/workspace"
RM_P2=0
RM_P4=0
RM_SUBMODULE=0
RM_ENVSLASH=0
GITLINK_DIRS=()

rm_note() { # class file line problem-text
  note "$2" "$3" "UNVERIFIED (repo-mode: $1) $4"
  case "$1" in
    P2) RM_P2=$((RM_P2 + 1)) ;;
    P4) RM_P4=$((RM_P4 + 1)) ;;
    SUBMODULE) RM_SUBMODULE=$((RM_SUBMODULE + 1)) ;;
    ENVSLASH) RM_ENVSLASH=$((RM_ENVSLASH + 1)) ;;
  esac
  return 0
}

is_envslash_payload() { # raw CIT payload path[:linespec] -> 0 when ENVSLASH-shaped
  local rp=${1%:*}
  case "$rp" in
    *env.sh|*env-go.sh|*env-container.sh) : ;;
    *) return 1 ;;
  esac
  case "$rp" in
    ../*|/*) return 0 ;;
  esac
  return 1
}

load_gitlinks() { # gitlink (submodule) directories, read from the index
  [ "${#GITLINK_DIRS[@]}" -gt 0 ] && return 0
  local m sha st p
  while read -r m sha st p; do
    [ "$m" = "160000" ] || continue
    [ -n "$p" ] && GITLINK_DIRS+=("$p")
  done < <(git -C "$REPO_ROOT" ls-files -s 2>/dev/null)
  return 0
}

under_gitlink() { # path -> 0 when it lies inside a gitlink directory
  [ "${#GITLINK_DIRS[@]}" -gt 0 ] || return 1
  local p=${1#"$REPO_ROOT"/} d
  for d in "${GITLINK_DIRS[@]}"; do
    case "$p" in "$d"/*) return 0 ;; esac
  done
  return 1
}

audit_repo_class() { # raw citation path -> the gate's class name, or empty
  local p=$1
  if is_envslash_payload "$p"; then printf 'ENVSLASH'; return 0; fi
  if under_gitlink "$p"; then printf 'SUBMODULE'; return 0; fi
  case "$p" in
    "$CONTAINER_ROOT"/*) printf 'P4'; return 0 ;;
  esac
  if is_p2_payload "$p"; then printf 'P2'; return 0; fi
  printf ''
}

if [ "$REPO_MODE" = 1 ]; then load_gitlinks; fi

# -----------------------------------------------------------------------------
# Output buffering: every record is sorted at the end so the whole run is
# deterministic. Record: order <TAB> zid <TAB> path <TAB> line <TAB> seq
#                       <TAB> kind <TAB> message
# -----------------------------------------------------------------------------
FINDINGS="$AUDIT_TMP/findings.tsv"
: > "$FINDINGS"
SEQ=0
CHECKS=0

fail() { # zid path line message
  SEQ=$((SEQ + 1))
  printf '1\t%s\t%s\t%s\t%s\tFAIL\t%s\n' "$1" "$2" "$3" "$SEQ" "$4" >> "$FINDINGS"
}
warn() { # path line message
  SEQ=$((SEQ + 1))
  printf '2\t-\t%s\t%s\t%s\tWARN\t%s\n' "$1" "$2" "$SEQ" "$3" >> "$FINDINGS"
}
note() { # path line message
  SEQ=$((SEQ + 1))
  printf '3\t-\t%s\t%s\t%s\tNOTE\t%s\n' "$1" "$2" "$SEQ" "$3" >> "$FINDINGS"
}

count_lines() {
  if [ -z "${1:-}" ]; then printf '0'; else printf '%s\n' "$1" | grep -c ''; fi
}

in_list() { # needle, then list
  local n=$1 x
  shift
  for x in "$@"; do [ "$x" = "$n" ] && return 0; done
  return 1
}

# switcher_count <file> <side> -> number of frozen switcher lines among the first
# SWITCHER_WINDOW lines. Used only to decide whether a pair is armed: this is the
# audit-side mirror of the checker's `has_switcher` arming rule (doc-verify.sh
# V14: a pair is judged as soon as one side carries an i18n marker).
switcher_count() { # file side(zh|en)
  local file=$1 side=$2 re
  [ -f "$file" ] || { printf '0'; return 0; }
  if [ "$side" = zh ]; then
    re='^> \*\*中文（默认）\*\* · \[English\]\([^()]+\)$'
  else
    re='^> \[中文（默认）\]\([^()]+\) · English$'
  fi
  count_lines "$(head -n "$SWITCHER_WINDOW" -- "$file" | grep -E -- "$re" || true)"
}

# -----------------------------------------------------------------------------
# Target set (--only) / full mode
# -----------------------------------------------------------------------------
FULL=1
TARGETS=()
if [ "${#ONLY[@]}" -gt 0 ]; then
  FULL=0
  for p in "${ONLY[@]}"; do
    if [ -d "$p" ]; then
      while IFS= read -r f; do
        TARGETS+=("$(realpath -m --relative-to="$REPO_ROOT" -- "$f")")
      done < <(find "$p" -name '*.md' -type f | LC_ALL=C sort)
    elif [ -f "$p" ]; then
      TARGETS+=("$(realpath -m --relative-to="$REPO_ROOT" -- "$p")")
    else
      printf 'i18n-audit.sh: --only target does not exist: %s\n' "$p" >&2
      exit 2
    fi
  done
fi

is_target() { # repo-relative path
  [ "$FULL" = 1 ] && return 0
  in_list "$1" "${TARGETS[@]}"
}

zh_tree_files() {
  find "$ZH_TREE" -name '*.md' -type f | LC_ALL=C sort
}

# =============================================================================
# Z1 — bilingual switcher
# =============================================================================
z1_check() { # file
  local file=$1 i side=- idx=-
  CHECKS=$((CHECKS + 1))
  for i in "${!PAIR_ZH[@]}"; do
    if [ "${PAIR_ZH[$i]}" = "$file" ]; then idx=$i; side=zh; break; fi
  done
  if [ "$idx" = - ]; then
    for i in "${!PAIR_EN[@]}"; do
      if [ "${PAIR_EN[$i]}" = "$file" ]; then idx=$i; side=en; break; fi
    done
  fi
  if [ "$idx" = - ]; then
    fail Z1 "$file" 1 "file is not part of the frozen pair list → drop it from the audit scope or register the pair (GLOSSARY §3.4)"
    return 0
  fi
  local re
  if [ "$side" = zh ]; then
    re='^> \*\*中文（默认）\*\* · \[English\]\([^()]+\)$'
  else
    re='^> \[中文（默认）\]\([^()]+\) · English$'
  fi
  local hits n ln lh target expected dir norm exp got any aln where
  hits=$(head -n "$SWITCHER_WINDOW" -- "$file" | grep -nE -- "$re" || true)
  n=$(count_lines "$hits")
  if [ "$n" = 1 ]; then
    ln=${hits%%:*}
    lh=$(sed -n "${ln}p" -- "$file")
    target=$(printf '%s\n' "$lh" | sed -n 's/^.*](\([^()]*\)).*$/\1/p')
    if [ -z "$target" ]; then
      fail Z1 "$file" "$ln" "switcher link target could not be parsed → write the frozen switcher line from GLOSSARY §3.2/§3.3"
      return 0
    fi
    if [ "$side" = zh ]; then expected=${PAIR_EN[$idx]}; else expected=${PAIR_ZH[$idx]}; fi
    dir=$(dirname -- "$file")
    norm=$(realpath -m -- "$dir/$target")
    exp=$(realpath -m -- "$REPO_ROOT/$expected")
    if [ ! -e "$norm" ]; then
      fail Z1 "$file" "$ln" "switcher target does not exist: $target → point the switcher at the counterpart $expected"
    elif [ "$norm" != "$exp" ]; then
      got=$(realpath -m --relative-to="$REPO_ROOT" -- "$norm")
      fail Z1 "$file" "$ln" "switcher target resolves to $got, not the counterpart $expected → point the switcher at $expected"
    fi
    return 0
  fi
  any=$(head -n "$SWITCHER_WINDOW" -- "$file" | grep -nE '\*\*中文（默认）\*\*|\[中文（默认）\]|\[English\]' || true)
  aln=${any%%:*}
  where=${aln:-1}
  if [ "$n" = 0 ]; then
    if [ -n "$any" ]; then
      if [ "$side" = zh ]; then
        fail Z1 "$file" "$where" "switcher label does not match the frozen 中文（默认） form → write the frozen switcher line from GLOSSARY §3.2 with half-width parentheses"
      else
        fail Z1 "$file" "$where" "switcher label does not match the frozen [中文（默认）] reverse-link form → write the frozen switcher line from GLOSSARY §3.3 with half-width parentheses"
      fi
    else
      fail Z1 "$file" 1 "missing language switcher in the first $SWITCHER_WINDOW lines → add the frozen switcher line from GLOSSARY §3.2/§3.3"
    fi
  else
    fail Z1 "$file" "$where" "more than one language switcher in the first $SWITCHER_WINDOW lines (found $n) → keep exactly one switcher line"
  fi
}

# =============================================================================
# Z2 — verbatim translation disclaimer
# =============================================================================
z2_file() { # file
  CHECKS=$((CHECKS + 1))
  local hits n
  hits=$(head -n "$SWITCHER_WINDOW" -- "$1" | grep -nxF -- "$DISCLAIMER" || true)
  n=$(count_lines "$hits")
  if [ "$n" != 1 ]; then
    fail Z2 "$1" 1 "translation disclaimer is not present exactly once in the first $SWITCHER_WINDOW lines (found $n) → write the frozen line from GLOSSARY §4 Y-2 verbatim"
  fi
}

z2_global() { # full mode only
  CHECKS=$((CHECKS + 1))
  find . -name '*.md' -type f -not -path './.git/*' | LC_ALL=C sort > "$AUDIT_TMP/z2.files"
  xargs -a "$AUDIT_TMP/z2.files" awk -v target="$DISCLAIMER" '
    FNR == 1 { infence = 0 }
    {
      if ($0 ~ /^[[:space:]]*(```|~~~)/) { infence = !infence; next }
      if (infence) next
      if ($0 == target) print FILENAME ":" FNR
    }' > "$AUDIT_TMP/z2.hits"
  local total expected f ln n
  total=$(count_lines "$(cat "$AUDIT_TMP/z2.hits")")
  # Existence-armed expectation (reports/66 §3.4.3 + captain ruling): the count
  # is taken over the §4 Y-1 pages that exist on disk, so a partially landed
  # NN = 01–11 set is never reported as a failure for pages not on disk yet.
  expected=0
  for f in "${Y1[@]}"; do [ -f "$f" ] && expected=$((expected + 1)); done
  while IFS= read -r hit; do
    [ -z "$hit" ] && continue
    f=${hit%:*}
    ln=${hit##*:}
    f=${f#./}
    if ! in_list "$f" "${Y1[@]}"; then
      fail Z2 "$f" "$ln" "translation disclaimer appears outside the §4 Y-1 list → remove it (Y-4 exemption) or register the file as a translated page"
    fi
  done < "$AUDIT_TMP/z2.hits"
  # Every existing Y-1 page must contribute exactly one non-fenced whole-line
  # occurrence; pages that are not on disk are skipped, not failed.
  for f in "${Y1[@]}"; do
    [ -f "$f" ] || continue
    n=$(awk -F: -v p="./$f" '$1 == p { c++ } END { print c + 0 }' "$AUDIT_TMP/z2.hits")
    if [ "$n" != 1 ]; then
      fail Z2 "$f" 1 "the verbatim disclaimer occurs $n times outside fenced code blocks in this existing §4 Y-1 file, expected exactly 1 → keep exactly one occurrence per existing Y-1 page"
    fi
  done
  if [ "$total" != "$expected" ]; then
    fail Z2 "$GLOSSARY" 1 "the verbatim disclaimer occurs $total times outside fenced code blocks, expected $expected (one per §4 Y-1 file that exists on disk; $expected of ${#Y1[@]} registered) → keep exactly one occurrence per existing Y-1 file"
  fi
  note "scripts/i18n-audit.sh" 1 "Z2: disclaimer occurrences = $total, expected = $expected (= |Y-1 ∩ disk| of ${#Y1[@]} registered Y-1 pages)"
}

# =============================================================================
# Z3 — GLOSSARY §6 terms table structure
# =============================================================================
z3_check() {
  CHECKS=$((CHECKS + 1))
  awk '
    /^## 6\./ { in6 = 1; next }
    in6 && /^## / { in6 = 0 }
    in6 && /^### / { in6 = 0; exit }
    in6 && /^\|/ {
      if ($0 ~ /^\|[[:space:]]*#[[:space:]]*\|/) next
      if ($0 ~ /^\|[-: |]+\|[[:space:]]*$/) next
      rows++
      n = split($0, c, "|")
      cnt = 0
      for (i = 2; i < n; i++) {
        v = c[i]
        gsub(/^[[:space:]]+/, "", v)
        gsub(/[[:space:]]+$/, "", v)
        if (v != "") cnt++
      }
      idx = c[2]
      gsub(/[[:space:]]/, "", idx)
      if (cnt != 4) print "CELLS\t" FNR "\t" cnt
      if (idx + 0 != rows) print "IDX\t" FNR "\t" idx "\t" rows
    }
    END { print "ROWS\t" rows + 0 }
  ' "$GLOSSARY" > "$AUDIT_TMP/z3.out"
  local rows
  rows=$(awk -F'\t' '$1 == "ROWS" { print $2 }' "$AUDIT_TMP/z3.out")
  if [ "$rows" != 98 ]; then
    fail Z3 "$GLOSSARY" 1 "§6 table has $rows data rows, expected 98 → restore the frozen 98-row terms table (GLOSSARY §6)"
  fi
  local line n
  while IFS=$'\t' read -r kind l a b; do
    case "$kind" in
      CELLS)
        fail Z3 "$GLOSSARY" "$l" "§6 row has $a non-empty cells, expected 4 (#, 英文原词, 中文定译, 首现说明与边界) → restore the frozen row shape"
        ;;
      IDX)
        fail Z3 "$GLOSSARY" "$l" "§6 row number column is $a, expected $b (1..98 in order) → renumber the row"
        ;;
    esac
  done < "$AUDIT_TMP/z3.out"
  note "$GLOSSARY" 1 "Z3: §6 data rows = $rows, each with 4 non-empty cells and # = 1..$rows"
}

# =============================================================================
# Z4 — GLOSSARY §5.5 status words (full-width parentheses + frozen translation)
# =============================================================================
z4_check() { # file
  CHECKS=$((CHECKS + 1))
  awk '
    function lastidx(s, ch,   i, r, len) {
      r = 0; len = length(ch)
      for (i = 1; i <= length(s) - len + 1; i++) if (substr(s, i, len) == ch) r = i
      return r
    }
    function endswith(s, suf,   sl) {
      sl = length(suf)
      return (length(s) >= sl) && (substr(s, length(s) - sl + 1) == suf)
    }
    BEGIN {
      n = split("partially implemented|known limitation|implemented|unverified|rejected|disproven", EN, "|")
      split("部分实现|已知限制|已实现|未验证|已否决|已证伪", ZH, "|")
    }
    FNR == 1 { infence = 0 }
    {
      if ($0 ~ /^[[:space:]]*(```|~~~)/) { infence = !infence; next }
      if (infence) next
      line = $0
      flag = 0
      for (i = 1; i <= length(line); i++) {
        c = substr(line, i, 1)
        if (c == "`") { flag = 1 - flag; incode[i] = 0; continue }
        incode[i] = flag
      }
      for (p = 1; p <= n; p++) {
        en = EN[p]; zh = ZH[p]; el = length(en)
        start = 1
        while (1) {
          idx = index(substr(line, start), en)
          if (idx == 0) break
          pos = start + idx - 1
          start = pos + 1
          if (en == "implemented" && endswith(substr(line, 1, pos - 1), "partially ")) continue
          pre = substr(line, 1, pos - 1)
          oiF = lastidx(pre, "（"); oiH = lastidx(pre, "(")
          oi = (oiF > oiH) ? oiF : oiH
          if (oi == 0) continue
          fullOpen = (oiF > oiH) ? 1 : 0
          olen = fullOpen ? 3 : 1
          after = substr(line, pos + el)
          ci = fullOpen ? index(after, "）") : index(after, ")")
          if (ci == 0) continue
          closePos = pos + el - 1 + ci
          inner = substr(line, oi + olen, closePos - (oi + olen))
          if (fullOpen) {
            if (inner == en && endswith(substr(line, 1, oi - 1), zh)) hits++
            else if (inner == en) print "DEV\t" FILENAME "\t" FNR "\tstatus word inside full-width parentheses is not preceded by the frozen translation 《" zh "》 → write it as " zh "（" en "）（GLOSSARY §5.5）"
            else if (index(inner, en) > 0) print "DEV\t" FILENAME "\t" FNR "\tstatus word inside full-width parentheses must be exactly " zh "（" en "）→ remove the extra text inside the parentheses (GLOSSARY §5.5)"
          } else {
            if (index(inner, en) > 0 && incode[pos] != 1) print "DEV\t" FILENAME "\t" FNR "\ta status word in Chinese prose must use full-width parentheses → write it as " zh "（" en "）（GLOSSARY §5.3/§5.5）"
          }
        }
      }
    }
    END { print "HITS\t" hits + 0 }
  ' "$1" > "$AUDIT_TMP/z4.out"
  local hits line
  while IFS=$'\t' read -r kind f l msg; do
    [ "$kind" = DEV ] || continue
    fail Z4 "$f" "$l" "$msg"
  done < "$AUDIT_TMP/z4.out"
  hits=$(awk -F'\t' '$1 == "HITS" { print $2 }' "$AUDIT_TMP/z4.out")
  note "$1" 1 "Z4: status-word occurrences in full-width parentheses = $hits"
}

# =============================================================================
# Z5 — §6.1 narrow denylist and §6 status-word rows
# =============================================================================
z5_deny() { # file (one of the 12 translated pages)
  CHECKS=$((CHECKS + 1))
  awk -v file="$1" '
    index($0, "NEGATIVE EXAMPLE") > 0 { next }
    index($0, "座位") > 0 { print file "\t" FNR "\t座位" ; next }
    index($0, "会议室") > 0 { print file "\t" FNR "\t会议室" }
  ' "$1" > "$AUDIT_TMP/z5a.out"
  local f l tok
  while IFS=$'\t' read -r f l tok; do
    [ -z "$f" ] && continue
    fail Z5 "$f" "$l" "banned translation 《$tok》 appears in a translated page → use 席位 / 房间 (GLOSSARY §6.1)"
  done < "$AUDIT_TMP/z5a.out"
}

z5_status_rows() {
  CHECKS=$((CHECKS + 1))
  awk '
    /^## 6\./ { in6 = 1; next }
    in6 && /^## / { in6 = 0 }
    in6 && /^### / { in6 = 0; exit }
    in6 && /^\|/ {
      if ($0 ~ /^\|[[:space:]]*#[[:space:]]*\|/) next
      if ($0 ~ /^\|[-: |]+\|[[:space:]]*$/) next
      n = split($0, c, "|")
      zh = c[4]; k = c[5]
      gsub(/^[[:space:]]+/, "", zh); gsub(/[[:space:]]+$/, "", zh)
      gsub(/^[[:space:]]+/, "", k); gsub(/[[:space:]]+$/, "", k)
      if (index(k, "状态词") > 0) { rows++; print FNR "\t" zh }
    }
    END { print "ROWS\t" rows + 0 }
  ' "$GLOSSARY" > "$AUDIT_TMP/z5b.out"
  local rows l zh ok
  rows=$(awk -F'\t' '$1 == "ROWS" { print $2 }' "$AUDIT_TMP/z5b.out")
  while IFS=$'\t' read -r l zh; do
    [ "$l" = ROWS ] && continue
    ok=0
    for s in "${STATUS_ZH[@]}"; do [ "$zh" = "$s" ] && ok=1; done
    if [ "$ok" != 1 ]; then
      fail Z5 "$GLOSSARY" "$l" "§6 row whose 首现说明 mentions 状态词 has 中文定译 《$zh》, not one of the §5.5 frozen forms → use the frozen status word"
    fi
  done < "$AUDIT_TMP/z5b.out"
  note "$GLOSSARY" 1 "Z5: §6 rows whose 首现说明 mentions 状态词 = $rows"
}

# =============================================================================
# Z6/Z7/Z8 — pair equality (citation sets, code-span multisets, structure)
# =============================================================================
extract_cites() { # file -> raw <TAB> file <TAB> line
  awk -v file="$1" '
    FNR == 1 { infence = 0 }
    {
      if ($0 ~ /^[[:space:]]*(```|~~~)/) { infence = !infence; next }
      if (infence) next
      line = $0
      s = 1
      while (1) {
        i = index(substr(line, s), "`")
        if (i == 0) break
        a = s + i - 1
        j = index(substr(line, a + 1), "`")
        if (j == 0) break
        b = a + j
        c = substr(line, a + 1, b - a - 1)
        if (c ~ /^[^[:space:]]+:[0-9]+(-[0-9]+)?$/) {
          p = c
          sub(/:[0-9]+(-[0-9]+)?$/, "", p)
          if (p ~ /\.[A-Za-z0-9_]+$/) printf "%s\t%s\t%s\n", c, file, FNR
        }
        s = b + 1
      }
    }' "$1"
}

extract_spans() { # file -> span <TAB> file <TAB> line
  awk -v file="$1" '
    FNR == 1 { infence = 0 }
    {
      if ($0 ~ /^[[:space:]]*(```|~~~)/) { infence = !infence; next }
      if (infence) next
      line = $0
      s = 1
      while (1) {
        i = index(substr(line, s), "`")
        if (i == 0) break
        a = s + i - 1
        j = index(substr(line, a + 1), "`")
        if (j == 0) break
        b = a + j
        c = substr(line, a + 1, b - a - 1)
        if (c != "") printf "%s\t%s\t%s\n", c, file, FNR
        s = b + 1
      }
    }' "$1"
}

file_lines() { awk 'END { print NR + 0 }' "$1"; }

# is_p2_payload <raw citation path> -> 0 when the gate's P2 classification
# applies. The gate's class pattern is `../*|tmp/*|*env.sh|*env-container.sh|
# *env-go.sh` (doc-verify.sh §P2); Z6 mirrors it literally so the two scripts
# classify the same payload the same way (captain revision b2).
is_p2_payload() {
  case "$1" in
    ../*|tmp/*|*env.sh|*env-container.sh|*env-go.sh) return 0 ;;
  esac
  return 1
}

# p2_target_path <raw P2 path> -> "$WORKSPACE_ROOT/<path with every leading
# ../ stripped>", exactly the gate's p2-workspace resolution. The caller still
# requires the result to exist, so a missing P2 target stays a FAIL[Z6]; no
# branch turns "not found" into a pass.
p2_target_path() {
  local rp=$1
  while [ "${rp#../}" != "$rp" ]; do rp=${rp#../}; done
  printf '%s/%s' "$WORKSPACE_ROOT" "$rp"
}

norm_cite() { # file raw -> "repo-relative:linespec" on stdout, or empty
  local file=$1 raw=$2 p spec dir cand rel
  p=${raw%:*}
  spec=${raw#"$p"}
  dir=$(dirname -- "$file")
  if [ -e "$dir/$p" ]; then
    rel=$(realpath -m --relative-to="$REPO_ROOT" -- "$dir/$p")
  else
    rel=$(realpath -m --relative-to="$REPO_ROOT" -- "$p")
  fi
  printf '%s%s' "$rel" "$spec"
}

z6_pair() { # idx
  local idx=$1 en=${PAIR_EN[$1]} zh=${PAIR_ZH[$1]}
  # Existence-armed (reports/66 §3.4.3 + captain rulings b1/b2): the equality
  # obligations apply once both sides are on disk; an armed pair whose
  # counterpart is absent is already a FAIL[Z1], so returning here never
  # weakens the verdict, and an unarmed registered pair is skipped.
  [ -f "$en" ] && [ -f "$zh" ] || return 0
  local d="$AUDIT_TMP/p$idx"
  mkdir -p "$d"
  CHECKS=$((CHECKS + 1))
  extract_cites "$en" > "$d/en.raw"
  extract_cites "$zh" > "$d/zh.raw"
  local side f raw ln norm
  for side in en zh; do
    [ "$side" = en ] && f=$en || f=$zh
    : > "$d/$side.norm"
    while IFS=$'\t' read -r raw file ln; do
      [ -z "$raw" ] && continue
      p=${raw%:*}
      spec=${raw#"$p"}
      norm=$(norm_cite "$file" "$raw")
      printf '%s\t%s\t%s\t%s\n' "$norm" "$file" "$ln" "$raw" >> "$d/$side.norm"
      # Existence and line bounds are the own obligation of the file that
      # carries the citation; in --only mode an unlisted counterpart is read
      # but not asserted against.
      is_target "$file" || continue
      local target="${norm%:*}"
      local range="${norm##*:}" a b nlines fs
      a=${range%%-*}
      b=${range##*-}
      # Resolution mirrors the gate (captain revision b2): a P2-type payload is
      # opened under WORKSPACE_ROOT, everything else under REPO_ROOT. Both sides
      # of a pair go through this same code, so the mapping stays symmetric.
      if is_p2_payload "$p"; then
        fs=$(p2_target_path "$p")
      else
        fs="$REPO_ROOT/$target"
      fi
      if [ ! -e "$fs" ]; then
        if [ "$REPO_MODE" = 1 ]; then
          rmclass=$(audit_repo_class "$p")
          if [ -n "$rmclass" ]; then
            rm_note "$rmclass" "$file" "$ln" "citation target does not exist: \`$raw\`"
            continue
          fi
        fi
        fail Z6 "$file" "$ln" "citation target does not exist: \`$raw\` → fix the path or drop the citation"
        continue
      fi
      nlines=$(file_lines "$fs")
      if [ "$a" -lt 1 ] || [ "$b" -gt "$nlines" ] || [ "$b" -lt "$a" ]; then
        fail Z6 "$file" "$ln" "citation \`$raw\` is out of bounds (target has $nlines lines) → correct the line number"
      fi
    done < "$d/$side.raw"
    LC_ALL=C sort -u -t "$(printf '\t')" -k1,1 "$d/$side.norm" > "$d/$side.set"
    note "$f" 1 "Z6: deduplicated citations = $(count_lines "$(cat "$d/$side.set")") ($(count_lines "$(cat "$d/$side.raw")") occurrences)"
  done
  is_target "$en" || is_target "$zh" || return 0
  cut -f1 "$d/en.norm" | LC_ALL=C sort > "$d/en.sorted"
  cut -f1 "$d/zh.norm" | LC_ALL=C sort > "$d/zh.sorted"
  # entries present only on one side
  local other own
  comm -23 "$d/en.sorted" "$d/zh.sorted" > "$d/only-en"
  comm -13 "$d/en.sorted" "$d/zh.sorted" > "$d/only-zh"
  _z6_report_side "$idx" en "$d/only-en" "$zh"
  _z6_report_side "$idx" zh "$d/only-zh" "$en"
}

_z6_report_side() { # idx side listfile counterpart
  local idx=$1 side=$2 list=$3 other=$4 own
  [ -s "$list" ] || return 0
  if [ "$side" = en ]; then own=${PAIR_EN[$idx]}; else own=${PAIR_ZH[$idx]}; fi
  local entry loc ln
  while IFS= read -r entry; do
    [ -z "$entry" ] && continue
    if is_target "$own"; then
      loc=$(awk -F'\t' -v s="$entry" '$1 == s { print $2 ":" $3; exit }' "$AUDIT_TMP/p$idx/$side.norm")
      ln=${loc##*:}
      fail Z6 "$own" "$ln" "citation \`$entry\` is missing from the counterpart ($other) → mirror the citation on both sides (GLOSSARY §5.4)"
    elif is_target "$other"; then
      fail Z6 "$other" 1 "citation \`$entry\` exists on the other side but is missing here → mirror the citation on both sides (GLOSSARY §5.4)"
    fi
  done < "$list"
}

z7_pair() { # idx
  local idx=$1 en=${PAIR_EN[$1]} zh=${PAIR_ZH[$1]}
  is_target "$en" || is_target "$zh" || return 0
  # Existence-armed: equality arms only when both sides exist (see z6_pair).
  [ -f "$en" ] && [ -f "$zh" ] || return 0
  local d="$AUDIT_TMP/p$idx"
  mkdir -p "$d"
  CHECKS=$((CHECKS + 1))
  extract_spans "$en" > "$d/en.spans"
  extract_spans "$zh" > "$d/zh.spans"
  note "$en" 1 "Z7: inline code spans = $(count_lines "$(cat "$d/en.spans")")"
  note "$zh" 1 "Z7: inline code spans = $(count_lines "$(cat "$d/zh.spans")")"
  cut -f1 "$d/en.spans" | LC_ALL=C sort > "$d/en.sorted"
  cut -f1 "$d/zh.spans" | LC_ALL=C sort > "$d/zh.sorted"
  comm -23 "$d/en.sorted" "$d/zh.sorted" > "$d/only-en"
  comm -13 "$d/en.sorted" "$d/zh.sorted" > "$d/only-zh"
  _z7_report_side "$idx" en "$d/only-en" "$zh"
  _z7_report_side "$idx" zh "$d/only-zh" "$en"
}

_z7_report_side() { # idx side listfile counterpart
  local idx=$1 side=$2 list=$3 other=$4 own
  [ -s "$list" ] || return 0
  if [ "$side" = en ]; then own=${PAIR_EN[$idx]}; else own=${PAIR_ZH[$idx]}; fi
  local span loc ln
  while IFS= read -r span; do
    [ -z "$span" ] && continue
    if is_target "$own"; then
      loc=$(awk -F'\t' -v s="$span" '$1 == s { print $2 ":" $3; exit }' "$AUDIT_TMP/p$idx/$side.spans")
      ln=${loc##*:}
      fail Z7 "$own" "$ln" "inline code span \`$span\` is missing from the counterpart ($other) → keep code spans byte-identical (GLOSSARY §5.1/§5.4)"
    elif is_target "$other"; then
      fail Z7 "$other" 1 "inline code span \`$span\` exists on the other side but is missing here → keep code spans byte-identical (GLOSSARY §5.1/§5.4)"
    fi
  done < "$list"
}

z8_pair() { # idx
  local idx=$1 en=${PAIR_EN[$1]} zh=${PAIR_ZH[$1]}
  is_target "$en" || is_target "$zh" || return 0
  # Existence-armed: equality arms only when both sides exist (see z6_pair).
  [ -f "$en" ] && [ -f "$zh" ] || return 0
  local d="$AUDIT_TMP/p$idx"
  mkdir -p "$d"
  CHECKS=$((CHECKS + 1))
  awk '
    FNR == 1 { infence = 0; h2 = 0; h3 = 0; tbl = 0; blocks = 0 }
    {
      if ($0 ~ /^[[:space:]]*(```|~~~)/) { if (!infence) blocks++; infence = !infence; next }
      if (infence) next
      if ($0 ~ /^## /) h2++
      if ($0 ~ /^### /) h3++
      if ($0 ~ /^\|/ && $0 !~ /^\|[-: |]+\|[[:space:]]*$/) tbl++
    }
    END { printf "%d %d %d %d\n", h2, h3, tbl, blocks }
  ' "$en" > "$d/en.struct"
  awk '
    FNR == 1 { infence = 0; h2 = 0; h3 = 0; tbl = 0; blocks = 0 }
    {
      if ($0 ~ /^[[:space:]]*(```|~~~)/) { if (!infence) blocks++; infence = !infence; next }
      if (infence) next
      if ($0 ~ /^## /) h2++
      if ($0 ~ /^### /) h3++
      if ($0 ~ /^\|/ && $0 !~ /^\|[-: |]+\|[[:space:]]*$/) tbl++
    }
    END { printf "%d %d %d %d\n", h2, h3, tbl, blocks }
  ' "$zh" > "$d/zh.struct"
  local en_c zh_c
  en_c=$(cat "$d/en.struct")
  zh_c=$(cat "$d/zh.struct")
  note "$en" 1 "Z8: h2/h3/table-rows/fenced-blocks = $en_c"
  note "$zh" 1 "Z8: h2/h3/table-rows/fenced-blocks = $zh_c"
  [ "$en_c" = "$zh_c" ] && return 0
  local metric i ev zv label
  for i in 1 2 3 4; do
    case "$i" in
      1) label="h2 headings" ;;
      2) label="h3 headings" ;;
      3) label="table data rows" ;;
      4) label="fenced code blocks" ;;
    esac
    ev=$(printf '%s\n' "$en_c" | cut -d' ' -f"$i")
    zv=$(printf '%s\n' "$zh_c" | cut -d' ' -f"$i")
    [ "$ev" = "$zv" ] && continue
    if is_target "$en"; then
      fail Z8 "$en" 1 "$label count is $ev, the counterpart ($zh) has $zv → restore the frozen structure (GLOSSARY §5.4)"
    fi
    if is_target "$zh"; then
      fail Z8 "$zh" 1 "$label count is $zv, the counterpart ($en) has $ev → restore the frozen structure (GLOSSARY §5.4)"
    fi
  done
}

# =============================================================================
# Z9 — layout constraints
# =============================================================================
z9_forbidden() { # full mode only
  CHECKS=$((CHECKS + 1))
  local f
  for f in "${FORBIDDEN_COPIES[@]}"; do
    if [ -e "$f" ]; then
      fail Z9 "$f" 1 "language-suffixed copy of an entry/index page must not exist → delete it (SPEC R12/§7.6, GLOSSARY §2.1 L4)"
    fi
  done
}

z9_legacy() { # file
  CHECKS=$((CHECKS + 1))
  local hits ln
  hits=$(grep -nF -- "$LEGACY_SWITCHER" "$1" || true)
  while IFS= read -r hit; do
    [ -z "$hit" ] && continue
    ln=${hit%%:*}
    fail Z9 "$1" "$ln" "legacy switcher 《$LEGACY_SWITCHER》 still present → replace it with the frozen switcher line (GLOSSARY §3)"
  done <<< "$hits"
}

# =============================================================================
# Run
# =============================================================================
if [ "$FULL" = 1 ]; then
  # Existence-armed Z1 (reports/66 §3.4.3 + captain ruling; the arming rule
  # mirrors the checker's V14 `has_switcher` rule): a pair is judged as soon as
  # one side carries an i18n marker — the Chinese page exists under
  # doc/design/zh-CN/, an entry pair's English side exists, or either side
  # already carries its frozen switcher line. Pairs with no marker anywhere are
  # skipped; an armed pair whose counterpart is absent is a FAIL.
  ARMED_PAIRS=0
  SKIPPED_PAIRS=0
  for i in "${!PAIR_ZH[@]}"; do
    zf=${PAIR_ZH[$i]}
    ef=${PAIR_EN[$i]}
    armed=0
    case "$zf" in
      "$ZH_TREE"/*) [ -f "$zf" ] && armed=1 ;;
    esac
    if [ "$armed" = 0 ] && [ "$i" -lt 2 ] && [ -f "$ef" ]; then armed=1; fi
    if [ "$armed" = 0 ] && [ "$(switcher_count "$zf" zh)" != 0 ]; then armed=1; fi
    if [ "$armed" = 0 ] && [ "$(switcher_count "$ef" en)" != 0 ]; then armed=1; fi
    if [ "$armed" = 0 ]; then
      SKIPPED_PAIRS=$((SKIPPED_PAIRS + 1))
      continue
    fi
    ARMED_PAIRS=$((ARMED_PAIRS + 1))
    if [ -f "$zf" ]; then
      z1_check "$zf"
    else
      fail Z1 "$ef" 1 "paired Chinese page is missing: $zf → create it from the English original and add the frozen switcher line (GLOSSARY §3.4)"
    fi
    if [ -f "$ef" ]; then
      z1_check "$ef"
    else
      fail Z1 "$zf" 1 "paired English page is missing: $ef → restore the frozen pair (GLOSSARY §3.4)"
    fi
  done
  note "scripts/i18n-audit.sh" 1 "Z1: armed pairs = $ARMED_PAIRS, skipped (existence-armed, no i18n marker on either side) = $SKIPPED_PAIRS of ${#PAIR_ZH[@]} registered pairs"
  for f in "${Y1[@]}"; do [ -f "$f" ] && z2_file "$f"; done
  z2_global
  z3_check
  while IFS= read -r f; do z4_check "$f"; done < <(zh_tree_files)
  for f in "${Y1[@]}"; do [ -f "$f" ] && z5_deny "$f"; done
  z5_status_rows
  BODY_ARMED=0
  BODY_SKIPPED=0
  for i in "${BODY_PAIRS[@]}"; do
    if [ -f "${PAIR_EN[$i]}" ] && [ -f "${PAIR_ZH[$i]}" ]; then
      BODY_ARMED=$((BODY_ARMED + 1))
    else
      BODY_SKIPPED=$((BODY_SKIPPED + 1))
    fi
    z6_pair "$i"
    z7_pair "$i"
    z8_pair "$i"
  done
  note "scripts/i18n-audit.sh" 1 "Z6/Z7/Z8: armed body pairs = $BODY_ARMED, skipped (existence-armed, both sides required) = $BODY_SKIPPED of ${#BODY_PAIRS[@]} registered body pairs"
  z9_forbidden
  { for f in README.md README.en.md; do [ -f "$f" ] && printf '%s\n' "$f"; done
    find doc/design -name '*.md' -type f | LC_ALL=C sort; } > "$AUDIT_TMP/z9.files"
  while IFS= read -r f; do z9_legacy "$f"; done < "$AUDIT_TMP/z9.files"
else
  for t in "${TARGETS[@]}"; do
    case "$t" in
      "$GLOSSARY")
        z3_check
        z4_check "$t"
        continue
        ;;
      "$SPEC_GUIDE")
        in_list "$t" "${Y1[@]}" && z2_file "$t"
        z5_deny "$t"
        ;;
    esac
    i=
    for i in "${!PAIR_ZH[@]}"; do [ "${PAIR_ZH[$i]}" = "$t" ] && break; done
    if [ "$i" != "" ] && [ "${PAIR_ZH[$i]:-}" = "$t" ]; then
      z1_check "$t"
      in_list "$t" "${Y1[@]}" && z2_file "$t"
      z4_check "$t"
      z5_deny "$t"
      z6_pair "$i"
      z7_pair "$i"
      z8_pair "$i"
      z9_legacy "$t"
      continue
    fi
    i=
    for i in "${!PAIR_EN[@]}"; do [ "${PAIR_EN[$i]}" = "$t" ] && break; done
    if [ "$i" != "" ] && [ "${PAIR_EN[$i]:-}" = "$t" ]; then
      z1_check "$t"
      z6_pair "$i"
      z7_pair "$i"
      z8_pair "$i"
      z9_legacy "$t"
      continue
    fi
    case "$t" in
      README.md|README.en.md|doc/design/*) z9_legacy "$t" ;;
      *) : ;;
    esac
  done
fi

# =============================================================================
# Emit
# =============================================================================
SUMMARY=$(LC_ALL=C sort -t "$(printf '\t')" -k1,1n -k2,2 -k3,3 -k4,4n -k5,5n "$FINDINGS" | awk -F'\t' '
  $6 == "FAIL" { printf "%s:%s → FAIL[%s] %s\n", $3, $4, $2, $7; f++ }
  $6 == "WARN" { printf "WARN %s:%s → %s\n", $3, $4, $7; w++ }
  $6 == "NOTE" { printf "NOTE %s:%s → %s\n", $3, $4, $7; n++ }
  END { printf "__COUNTS__ %d %d %d\n", f + 0, w + 0, n + 0 }
')
printf '%s\n' "$SUMMARY" | grep -v '^__COUNTS__' || true
COUNTS=$(printf '%s\n' "$SUMMARY" | grep '^__COUNTS__' | awk '{ print $2 " " $3 " " $4 }')
FAILURES=$(printf '%s' "$COUNTS" | awk '{ print $1 + 0 }')
WARNINGS=$(printf '%s' "$COUNTS" | awk '{ print $2 + 0 }')
if [ "$REPO_MODE" = 1 ]; then
  printf 'i18n-audit.sh: repo-mode summary (downgraded: P2=%d P4=%d SUBMODULE=%d ENVSLASH=%d; default-mode failures=%d; remaining failures=%d)\n' \
    "$RM_P2" "$RM_P4" "$RM_SUBMODULE" "$RM_ENVSLASH" \
    "$((FAILURES + RM_P2 + RM_P4 + RM_SUBMODULE + RM_ENVSLASH))" "$FAILURES"
fi
if [ "$FAILURES" -gt 0 ]; then
  printf 'i18n-audit.sh: FAIL (%d failures, %d warnings, %d checks)\n' "$FAILURES" "$WARNINGS" "$CHECKS"
  exit 1
fi
printf 'i18n-audit.sh: PASS (%d checks, %d warnings)\n' "$CHECKS" "$WARNINGS"
exit 0
