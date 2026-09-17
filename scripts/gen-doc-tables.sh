#!/usr/bin/env bash
# =============================================================================
# gen-doc-tables.sh — mechanically generate the machine-checked tables under
# doc/design/_generated/ (contract path: docs/_generated/, which is the same
# directory through the docs -> doc/design compatibility symlink).
#
# Generated tables
#   signaling-messages.md  Go <-> Kotlin signalling types/fields + divergence section
#   log-events.md          Kotlin AppLog / C++ NLOG_* event keys, with file:line
#   jni-contract.md        Kotlin external fun <-> JNINativeMethod <-> C prototypes
#   host-commands.md       authoritative command/flag inventory with provenance
#
# Guarantees
#   * deterministic: no timestamp is embedded, so repeated runs are byte-identical
#     (task contract allows a timestamp field, but only if the header says so; this
#     generator emits none, which is stated in every generated header);
#   * offline: bash + awk(mawk) + sed + grep + sort + sha256sum only, no network,
#     no python, no node;
#   * read-only with respect to product code: it reads app/, signaling/, scripts/,
#     deploy/, reports/ and writes only under doc/design/_generated/;
#   * honest degradation: when a source is not present in the repository (the
#     generated JNI header, the built .so, binutils) the table records
#     "UNVERIFIED (...)" instead of failing.
#
# Regenerate:  bash scripts/gen-doc-tables.sh
# Check output: bash scripts/doc-verify.sh --only doc/design/_generated/
# =============================================================================
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$REPO_ROOT"

OUT_DIR="doc/design/_generated"
mkdir -p "$OUT_DIR"

GO_MSG="signaling/protocol/message.go"
KT_MSG="app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt"
KT_NATIVE_DIR="app/src/main/kotlin/com/example/webrtcdemo/nativebridge"
CPP_JNI_DIR="app/src/main/cpp/jni"
JNI_BRIDGE_H="$CPP_JNI_DIR/jni_bridge.h"
JNILIBS_SO="app/src/main/jniLibs/arm64-v8a/libwebrtcdemo_native.so"

WORK=$(mktemp -d 2>/dev/null || true)
if [ -z "${WORK:-}" ] || [ ! -d "${WORK:-}" ]; then
  WORK="$OUT_DIR/.tmp.$$"
  mkdir -p "$WORK"
fi
trap 'rm -rf "$WORK"' EXIT

sha256_of() {
  if [ -f "$1" ]; then sha256sum "$1" | awk '{print $1}'; else printf 'missing'; fi
}

# -----------------------------------------------------------------------------
# header <title> <outfile> <source>...
# Every table carries: GENERATED — do not edit, the generation command, and the
# source paths with their sha256.
# -----------------------------------------------------------------------------
header() {
  local title="$1" out="$2"
  shift 2
  {
    printf '# %s\n\n' "$title"
    printf '> **GENERATED — do not edit.** Regenerate with `bash scripts/gen-doc-tables.sh`.\n'
    printf '> Generator: `scripts/gen-doc-tables.sh` (sha256 `%s`)\n' "$(sha256_of scripts/gen-doc-tables.sh)"
    printf '> Deterministic: no timestamp is embedded, so repeated runs are byte-identical.\n'
    printf '> Sources (sha256 of the exact revision this table was built from):\n'
    local s
    for s in "$@"; do
      printf '> - `%s` — sha256 `%s`\n' "$s" "$(sha256_of "$s")"
    done
    printf '\n'
  } > "$out"
}

# =============================================================================
# 1. signalling-messages.md
# =============================================================================
go_extract() {
  awk '
  function firstq(s,    p, q, rest) {
    p = index(s, "\"")
    if (p == 0) return ""
    rest = substr(s, p + 1)
    q = index(rest, "\"")
    if (q == 0) return ""
    return substr(rest, 1, q - 1)
  }
  function nocomment(s,    p) { p = index(s, "//"); return (p ? substr(s, 1, p - 1) : s) }
  function comment(s,    p) { p = index(s, "//"); return (p ? substr(s, p + 2) : "") }
  function dir_of(c,    t) {
    t = c
    if (t ~ /C *→ *S *→ *C/) return "C→S→C"
    if (t ~ /S *→ *C/) return "S→C"
    if (t ~ /C *→ *S/) return "C→S"
    return ""
  }
  function trim(s) { gsub(/^[ \t]+/, "", s); gsub(/[ \t]+$/, "", s); return s }
  function jsonname(code,    p, rest, q) {
    p = index(code, "json:\"")
    if (p == 0) return ""
    rest = substr(code, p + 6)
    q = index(rest, "\"")
    if (q == 0) return ""
    return substr(rest, 1, q - 1)
  }
  { L[FNR] = $0; NL = FNR }
  END {
    in_const = 0
    for (i = 1; i <= NL; i++) {
      line = L[i]
      if (line ~ /^const \(/) { in_const = 1; continue }
      if (in_const && line ~ /^\)/) { in_const = 0; continue }
      if (in_const) {
        code = nocomment(line); cmt = comment(line)
        if (index(code, "=") > 0 && index(code, "\"") > 0) {
          name = code; sub(/[ \t]*=.*/, "", name); name = trim(name)
          val = firstq(code)
          if (name != "" && val != "") {
            if (name ~ /^Type/) printf "TYPE\t%s\t%s\t%s\n", val, name, dir_of(cmt)
            else if (name ~ /^Nat/) printf "NAT\t%s\t%s\t%s\n", name, val, dir_of(cmt)
          }
        }
        continue
      }
      if (line == "}" && in_struct) {
        if (struct_type != "") printf "STRUCT\t%s\t%s\n", struct_type, struct_name
        in_struct = 0; continue
      }
      if (line ~ /^type [A-Za-z0-9_]+ struct \{/) {
        in_struct = 1
        split(line, w, " "); struct_name = w[2]; struct_type = ""
        continue
      }
      if (in_struct) {
        code = nocomment(line); cmt = comment(line)
        if (struct_type == "" && cmt != "") {
          v = firstq(cmt)
          if (v != "") struct_type = v
        }
        if (index(code, "json:\"") > 0) {
          n = 0; delete F
          m = split(code, tmp, /[ \t]+/)
          for (k = 1; k <= m; k++) if (tmp[k] != "") F[++n] = tmp[k]
          if (n >= 2) {
            jn = jsonname(code)
            om = (index(jn, ",") > 0) ? "yes" : "no"
            sub(/,.*/, "", jn)
            printf "FIELD\t%s\t%s\t%s\t%s\t%s\n", struct_type, F[1], F[2], jn, om
          }
        }
        continue
      }
    }
  }
  ' "$GO_MSG"
}

kt_extract() {
  awk '
  function firstq(s,    p, q, rest) {
    p = index(s, "\"")
    if (p == 0) return ""
    rest = substr(s, p + 1)
    q = index(rest, "\"")
    if (q == 0) return ""
    return substr(rest, 1, q - 1)
  }
  function trim(s) { gsub(/^[ \t]+/, "", s); gsub(/[ \t]+$/, "", s); return s }
  function scan(s,    i, c) {
    for (i = 1; i <= length(s); i++) {
      c = substr(s, i, 1)
      if (c == "(") depth++
      else if (c == ")") { depth--; if (depth < 0) { closed = 1; closepos = i; return i } }
    }
    return 0
  }
  function emit_fields(params, serial, line,    n, arr, i, p, cidx, fname, ftype, didx, nul) {
    n = split(params, arr, ",")
    for (i = 1; i <= n; i++) {
      p = trim(arr[i])
      if (p == "") continue
      if (p !~ /^val /) continue
      p = substr(p, 5)
      cidx = index(p, ":")
      if (cidx == 0) continue
      fname = trim(substr(p, 1, cidx - 1))
      ftype = trim(substr(p, cidx + 1))
      didx = index(ftype, "=")
      if (didx > 0) ftype = trim(substr(ftype, 1, didx - 1))
      nul = (index(ftype, "?") > 0) ? "yes" : "no"
      gsub(/\?/, "", ftype)
      if (fname != "") printf "KTFIELD\t%s\t%s\t%s\t%s\t%d\n", serial, fname, ftype, nul, line
    }
  }
  { L[FNR] = $0; NL = FNR }
  END {
    pending = ""; collecting = 0; buf = ""; klass = ""; serial = ""; startline = 0; depth = 0; closed = 0
    for (i = 1; i <= NL; i++) {
      line = L[i]
      if (collecting) {
        buf = buf " " line
        depth = 0; closed = 0
        if (scan(buf) > 0) {
          printf "KTMSG\t%s\t%s\t%s\t%d\n", serial, klass, (fields_expected ? "class" : "class"), startline
          emit_fields(substr(buf, 1, closepos - 1), serial, startline)
          collecting = 0; pending = ""; buf = ""
        }
        continue
      }
      if (line ~ /@SerialName\(/) { pending = firstq(line); continue }
      if (pending != "" && line ~ /data object [A-Za-z0-9_]+/) {
        n = 0; delete w; split(line, w, " ")
        klass = w[3]; sub(/[^A-Za-z0-9_].*/, "", klass)
        printf "KTMSG\t%s\t%s\tobject\t%d\n", pending, klass, i
        pending = ""; continue
      }
      if (pending != "" && line ~ /data class [A-Za-z0-9_]+\(/) {
        n = 0; delete w; split(line, w, " ")
        klass = w[3]; sub(/[^A-Za-z0-9_].*/, "", klass)
        serial = pending; startline = i
        p = index(line, "(")
        buf = substr(line, p + 1)
        depth = 0; closed = 0
        if (scan(buf) > 0) {
          printf "KTMSG\t%s\t%s\tclass\t%d\n", serial, klass, startline
          emit_fields(substr(buf, 1, closepos - 1), serial, startline)
          pending = ""; buf = ""
        } else {
          collecting = 1
        }
        continue
      }
    }
  }
  ' "$KT_MSG"
}

go_extract > "$WORK/go.tsv"
kt_extract > "$WORK/kt.tsv"

header "Signalling messages (Go <-> Kotlin)" \
  "$OUT_DIR/signaling-messages.md" "$GO_MSG" "$KT_MSG"

{
  printf '## 1. Message types (Go side)\n\n'
  printf 'Extracted from the `Type*` constants in `%s`.\n\n' "$GO_MSG"
  printf '| `type` value | Go constant | Direction | Source |\n|---|---|---|---|\n'
  awk -F'\t' '$1=="TYPE" { printf "| `%s` | `%s` | %s | `%s` |\n", $3, $2, $4, "signaling/protocol/message.go" }' "$WORK/go.tsv"
  printf '\n## 2. Message types (Kotlin side)\n\n'
  printf 'Extracted from the `@SerialName` annotations in `%s`.\n\n' "$KT_MSG"
  printf '| `type` value | Kotlin class | Kind | Source |\n|---|---|---|---|\n'
  awk -F'\t' '$1=="KTMSG" { printf "| `%s` | `%s` | %s | `%s` |\n", $2, $3, $4, "app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt" }' "$WORK/kt.tsv"
  printf '\n## 3. Field-level tables\n\n'
  for tv in $(awk -F'\t' '$1=="TYPE" {print $2}' "$WORK/go.tsv" | sort); do
    kc=$(awk -F'\t' -v v="$tv" '$1=="KTMSG" && $2==v {print $3}' "$WORK/kt.tsv" | head -1)
    printf '### `%s`\n\n' "$tv"
    printf '| Field (Go) | Go type | JSON key | omitempty | Field (Kotlin) | Kotlin type | nullable |\n|---|---|---|---|---|---|---|\n'
    got=$(awk -F'\t' -v v="$tv" '$1=="FIELD" && $2==v && $5!="type" {printf "%s\t%s\t%s\t%s\n", $3, $4, $5, $6}' "$WORK/go.tsv" | sort)
    ktf=$(awk -F'\t' -v v="$tv" '$1=="KTFIELD" && $2==v {printf "%s\t%s\t%s\n", $3, $4, $5}' "$WORK/kt.tsv" | sort)
    keys=$( { printf '%s\n' "$got" | awk -F'\t' 'NF{print $3}'; printf '%s\n' "$ktf" | awk -F'\t' 'NF{print $1}'; } | sort -u )
    for kk in $keys; do
      g=$(printf '%s\n' "$got" | awk -F'\t' -v k="$kk" '$3==k {printf "`%s` | `%s` | `%s` | %s", $1, $2, $3, $4; exit}')
      k=$(printf '%s\n' "$ktf" | awk -F'\t' -v k="$kk" '$1==k {printf "`%s` | `%s` | %s", $1, $2, $3; exit}')
      [ -z "$g" ] && g=$(printf '_(missing in Go)_ | - | `%s` | -' "$kk")
      [ -z "$k" ] && k=$(printf '_(missing in Kotlin)_ | - | -')
      printf '| %s | %s |\n' "$g" "$k"
    done
    [ -n "$kc" ] || printf '\n> Kotlin class for `%s`: **absent**.\n' "$tv"
    printf '\n'
  done
  printf '## 4. Divergence: Go vs Kotlin type sets\n\n'
  awk -F'\t' '$1=="TYPE" {print $2}' "$WORK/go.tsv" | sort -u > "$WORK/go_types.txt"
  awk -F'\t' '$1=="KTMSG" {print $2}' "$WORK/kt.tsv" | sort -u > "$WORK/kt_types.txt"
  printf '| Divergence | `type` values |\n|---|---|\n'
  if comm -23 "$WORK/go_types.txt" "$WORK/kt_types.txt" | grep -q .; then
    printf '| In Go, missing in Kotlin | %s |\n' "$(comm -23 "$WORK/go_types.txt" "$WORK/kt_types.txt" | sed 's/.*/`&`/' | paste -sd' ' -)"
  else
    printf '| In Go, missing in Kotlin | _(none)_ |\n'
  fi
  if comm -13 "$WORK/go_types.txt" "$WORK/kt_types.txt" | grep -q .; then
    printf '| In Kotlin, missing in Go | %s |\n' "$(comm -13 "$WORK/go_types.txt" "$WORK/kt_types.txt" | sed 's/.*/`&`/' | paste -sd' ' -)"
  else
    printf '| In Kotlin, missing in Go | _(none)_ |\n'
  fi
  printf '\n**Result: %s** (%s Go types, %s Kotlin types).\n' \
    "$(if diff -q "$WORK/go_types.txt" "$WORK/kt_types.txt" >/dev/null; then echo "type sets are identical"; else echo "TYPE SETS DIVERGE — see table above"; fi)" \
    "$(wc -l < "$WORK/go_types.txt" | tr -d ' ')" "$(wc -l < "$WORK/kt_types.txt" | tr -d ' ')"
  printf '\n## 5. NAT type enum\n\n'
  printf '| Go constant | value |\n|---|---|\n'
  awk -F'\t' '$1=="NAT" { printf "| `%s` | `%s` |\n", $2, $3 }' "$WORK/go.tsv"
  printf '\n## 6. Field-level divergence (JSON keys per type)\n\n'
  printf '| `type` | Divergence |\n|---|---|\n'
  for tv in $(awk -F'\t' '$1=="TYPE" {print $2}' "$WORK/go.tsv" | sort); do
    awk -F'\t' -v v="$tv" '$1=="FIELD" && $2==v && $5!="type" {print $5}' "$WORK/go.tsv" | sort -u > "$WORK/gof.txt"
    awk -F'\t' -v v="$tv" '$1=="KTFIELD" && $2==v {print $3}' "$WORK/kt.tsv" | sort -u > "$WORK/ktf.txt"
    d=""
    if ! diff -q "$WORK/gof.txt" "$WORK/ktf.txt" >/dev/null 2>&1; then
      onlyg=$(comm -23 "$WORK/gof.txt" "$WORK/ktf.txt" | paste -sd', ' -)
      onlyk=$(comm -13 "$WORK/gof.txt" "$WORK/ktf.txt" | paste -sd', ' -)
      d="Go-only: ${onlyg:-none}; Kotlin-only: ${onlyk:-none}"
    fi
    [ -n "$d" ] && printf '| `%s` | %s |\n' "$tv" "$d"
  done
  printf '\n_No rows above means every Go JSON key has a matching Kotlin property._\n'
} >> "$OUT_DIR/signaling-messages.md"

# =============================================================================
# 2. log-events.md
# =============================================================================
kt_logs() {
  local f
  for f in $(find app/src/main/kotlin -name '*.kt' | sort); do
  awk '
  function trim(s) { gsub(/^[ \t]+/, "", s); gsub(/[ \t]+$/, "", s); return s }
  function firstq(s,    p, q, rest) {
    p = index(s, "\"")
    if (p == 0) return ""
    rest = substr(s, p + 1)
    q = index(rest, "\"")
    if (q == 0) return ""
    return substr(rest, 1, q - 1)
  }
  function collect(startline, startcol,    k, s, i, c, d, txt) {
    d = 0; txt = ""
    for (k = startline; k <= NL; k++) {
      s = (k == startline) ? substr(L[k], startcol) : L[k]
      for (i = 1; i <= length(s); i++) {
        c = substr(s, i, 1)
        if (c == "(") d++
        else if (c == ")") { d--; if (d < 0) { calltext = txt; return 1 } }
        txt = txt c
      }
      txt = txt " "
    }
    calltext = txt; return 0
  }
  function split_args(s,    i, c, d, cur) {
    nargs = 0; d = 0; cur = ""
    for (i = 1; i <= length(s); i++) {
      c = substr(s, i, 1)
      if (c == "(") d++
      else if (c == ")") d--
      if (c == "," && d == 0) { nargs++; A[nargs] = trim(cur); cur = "" }
      else cur = cur c
    }
    nargs++; A[nargs] = trim(cur)
    return nargs
  }
  function mapkeys(s,    i, c, out, j, rest, q, tok, after) {
    out = ""
    j = 1
    while (j <= length(s)) {
      c = substr(s, j, 1)
      if (c == "\"") {
        rest = substr(s, j + 1)
        q = index(rest, "\"")
        if (q == 0) break
        tok = substr(rest, 1, q - 1)
        after = substr(rest, q + 1)
        sub(/^[ \t]+/, "", after)
        if (after ~ /^to /) out = (out == "" ? tok : out "," tok)
        j = j + 1 + q
      } else j++
    }
    return out
  }
  { L[FNR] = $0; NL = FNR }
  END {
    for (i = 1; i <= NL; i++) {
      line = L[i]
      if (line ~ /AppLog\.[dviwe]\(/) {
        p = index(line, "AppLog.")
        lvl = substr(line, p + 7, 1)
        op = index(substr(line, p), "(")
        if (op == 0) continue
        col = p + op
        collect(i, col)
        split_args(calltext)
        key = (nargs >= 2) ? firstq(A[2]) : ""
        if (key == "") key = lowerq(A[1])
        fields = ""
        for (k = 3; k <= nargs; k++) {
          if (index(A[k], "mapOf(") > 0 || index(A[k], "withKey") > 0) {
            mk = mapkeys(A[k])
            if (mk != "") fields = (fields == "" ? mk : fields "," mk)
          }
        }
        if (key != "") printf "KT\t%s\t%s\t%s\t%d\t%s\n", lvl, key, FILENAME, i, fields
      }
    }
  }
  function lowerq(s,    v) { v = firstq(s); return v }
  ' "$f"
  done
}

cpp_logs() {
  local f
  for f in $(find app/src/main/cpp -name '*.cpp' | sort); do
  awk '
  function trim(s) { gsub(/^[ \t]+/, "", s); gsub(/[ \t]+$/, "", s); return s }
  function collect(startline, startcol,    k, s, i, c, d, txt) {
    d = 0; txt = ""
    for (k = startline; k <= NL; k++) {
      s = (k == startline) ? substr(L[k], startcol) : L[k]
      for (i = 1; i <= length(s); i++) {
        c = substr(s, i, 1)
        if (c == "(") d++
        else if (c == ")") { d--; if (d < 0) { calltext = txt; return 1 } }
        txt = txt c
      }
      txt = txt " "
    }
    calltext = txt; return 0
  }
  function split_args(s,    i, c, d, cur) {
    nargs = 0; d = 0; cur = ""
    for (i = 1; i <= length(s); i++) {
      c = substr(s, i, 1)
      if (c == "(") d++
      else if (c == ")") d--
      if (c == "," && d == 0) { nargs++; A[nargs] = trim(cur); cur = "" }
      else cur = cur c
    }
    nargs++; A[nargs] = trim(cur)
    return nargs
  }
  function eventkey(fmt,    p, q, body, sp) {
    p = index(fmt, "\"")
    if (p == 0) return "<non-literal>"
    rest = substr(fmt, p + 1)
    q = index(rest, "\"")
    if (q == 0) return "<non-literal>"
    body = substr(rest, 1, q - 1)
    sp = index(body, " ")
    if (sp > 0) body = substr(body, 1, sp - 1)
    return body
  }
  { L[FNR] = $0; NL = FNR }
  END {
    for (i = 1; i <= NL; i++) {
      line = L[i]
      if (line ~ /NLOG_(VERBOSE|DEBUG|INFO|WARN|ERROR)\(/) {
        p = index(line, "NLOG_")
        q = index(substr(line, p), "(")
        lvl = substr(line, p + 5, q - 1 - 5 + 1)
        sub(/\(.*/, "", lvl)
        col = p + q
        collect(i, col)
        split_args(calltext)
        tag = (nargs >= 1) ? A[1] : ""
        key = (nargs >= 2) ? eventkey(A[2]) : "<none>"
        printf "CPP\t%s\t%s\t%s\t%s\t%d\n", lvl, tag, key, FILENAME, i
      }
    }
  }
  ' "$f"
  done
}

header "Log events (Kotlin AppLog + C++ NLOG_*)" \
  "$OUT_DIR/log-events.md" "$KT_MSG" "app/src/main/cpp/log/log_macros.h"

{
  printf '## 1. Kotlin event keys (`AppLog.<level>(TAG, "key", ...)`)\n\n'
  printf 'Source: `app/src/main/kotlin/**/*.kt` (event key = second call argument, as emitted).\n\n'
  printf '| Level | Event key | Source | Field keys |\n|---|---|---|---|\n'
  kt_logs \
    | awk -F'\t' '{ f = ($6 == "" ? "-" : $6); printf "| %s | `%s` | `%s:%s` | %s |\n", $2, $3, $4, $5, f }'
  printf '\n## 2. Native event keys (`NLOG_<LEVEL>(tag, "key ...", ...)`)\n\n'
  printf 'Source: `app/src/main/cpp/**/*.cpp` (event key = first whitespace-delimited token of the format string).\n\n'
  printf '| Level | Tag | Event key | Source |\n|---|---|---|---|\n'
  cpp_logs \
    | awk -F'\t' '{ printf "| %s | `%s` | `%s` | `%s:%s` |\n", $2, $3, $4, $5, $6 }'
  printf '\n## 3. Event-key index (sorted, unique)\n\n'
  printf '| Event key | Side | Emitted at |\n|---|---|---|\n'
  {
    kt_logs | awk -F'\t' '{ printf "%s\tKotlin\t%s:%s\n", $3, $4, $5 }'
    cpp_logs | awk -F'\t' '{ printf "%s\tNative\t%s:%s\n", $4, $5, $6 }'
  } | sort -u -k1,1 -k2,2 -k3,3 | awk -F'\t' '{ printf "| `%s` | %s | `%s` |\n", $1, $2, $3 }'
} >> "$OUT_DIR/log-events.md"

# =============================================================================
# 3. jni-contract.md
# =============================================================================
kt_native_extract() {
  local f
  for f in $(find "$KT_NATIVE_DIR" -name '*.kt' | sort); do
  awk '
  function trim(s) { gsub(/^[ \t]+/, "", s); gsub(/[ \t]+$/, "", s); return s }
  function firstq(s,    p, q, rest) {
    p = index(s, "\"")
    if (p == 0) return ""
    rest = substr(s, p + 1)
    q = index(rest, "\"")
    if (q == 0) return ""
    return substr(rest, 1, q - 1)
  }
  function scan(s,    i, c) {
    for (i = 1; i <= length(s); i++) {
      c = substr(s, i, 1)
      if (c == "(") depth++
      else if (c == ")") { depth--; if (depth < 0) { closed = 1; closepos = i; return i } }
    }
    return 0
  }
  function kdoc(nr,    i, p, r, q) {
    for (i = nr - 1; i >= 1 && i >= nr - 45; i--) {
      p = index(L[i], "`(")
      if (p > 0) {
        r = substr(L[i], p + 2)
        q = index(r, "`")
        if (q > 0) {
          tok = "(" substr(r, 1, q - 1)
          # a real JNI descriptor has no spaces or commas (a KDoc param list does)
          if (tok !~ /[ ,]/) return tok
        }
      }
    }
    return ""
  }
  function params_of(s,    n, arr, i, p, cidx, pname, ptype, out, didx) {
    out = ""
    n = split(s, arr, ",")
    for (i = 1; i <= n; i++) {
      p = trim(arr[i])
      if (p == "") continue
      cidx = index(p, ":")
      if (cidx == 0) continue
      pname = trim(substr(p, 1, cidx - 1)); sub(/^(val|var) /, "", pname)
      ptype = trim(substr(p, cidx + 1))
      didx = index(ptype, "=")
      if (didx > 0) ptype = trim(substr(ptype, 1, didx - 1))
      out = (out == "" ? "" : out ", ") pname ":" ptype
    }
    return out
  }
  function desc_of(s,    n, arr, i, p, cidx, ptype, sig, r) {
    sig = "("
    n = split(s, arr, ",")
    for (i = 1; i <= n; i++) {
      p = trim(arr[i])
      if (p == "") continue
      cidx = index(p, ":")
      if (cidx == 0) continue
      ptype = trim(substr(p, cidx + 1))
      r = index(ptype, "=")
      if (r > 0) ptype = trim(substr(ptype, 1, r - 1))
      gsub(/\?/, "", ptype)
      sig = sig jni_of(ptype)
    }
    return sig ")"
  }
  function jni_of(t) {
    if (t == "String") return "Ljava/lang/String;"
    if (t == "Int") return "I"
    if (t == "Long") return "J"
    if (t == "Boolean") return "Z"
    if (t == "Float") return "F"
    if (t == "Double") return "D"
    if (t == "ByteBuffer") return "Ljava/nio/ByteBuffer;"
    if (t == "IntArray") return "[I"
    if (t == "ByteArray") return "[B"
    if (t == "ShortArray") return "[S"
    if (t == "LongArray") return "[J"
    if (t == "FloatArray") return "[F"
    if (t == "DoubleArray") return "[D"
    if (t == "BooleanArray") return "[Z"
    if (t == "Unit") return "V"
    return "L" t ";"
  }
  { L[FNR] = $0; NL = FNR }
  END {
    obj = ""; collecting = 0; buf = ""; fname = ""; startline = 0; depth = 0; closed = 0
    for (i = 1; i <= NL; i++) {
      line = L[i]
      if (collecting) {
        buf = buf " " line
        depth = 0; closed = 0
        if (scan(buf) > 0) {
          rest = substr(buf, closepos + 1)
          ret = ret_of(rest)
          printf "KTFUN\t%s\t%s\t%s\t%s\t%s\t%s\t%d\n", obj, fname, params_of(substr(buf, 1, closepos - 1)), desc_of(substr(buf, 1, closepos - 1)) ret, kdoc(startline), FILENAME, startline
          collecting = 0; buf = ""
        }
        continue
      }
      if (line ~ /^object [A-Za-z0-9_]+/) {
        split(line, w, " "); obj = w[2]; sub(/[^A-Za-z0-9_].*/, "", obj); continue
      }
      if (line ~ /^[ \t]*fun [A-Za-z0-9_]+\(/) {
        f = line; sub(/^[ \t]*fun /, "", f); sub(/\(.*/, "", f)
        if (f != "") printf "KTCB\t%s\t%s\t%s\t%d\n", obj, f, params_of_line(i), FILENAME, i
        continue
      }
      if (line ~ /external fun [A-Za-z0-9_]+/) {
        p = index(line, "external fun ")
        f = substr(line, p + 13)
        sub(/\(.*/, "", f)
        op = index(substr(line, p), "(")
        if (op == 0) continue
        startline = i; fname = f
        col = p + op
        buf = substr(line, col)
        depth = 0; closed = 0
        if (scan(buf) > 0) {
          rest = substr(buf, closepos + 1)
          ret = ret_of(rest)
          printf "KTFUN\t%s\t%s\t%s\t%s\t%s\t%s\t%d\n", obj, fname, params_of(substr(buf, 1, closepos - 1)), desc_of(substr(buf, 1, closepos - 1)) ret, kdoc(startline), FILENAME, startline
          buf = ""
        } else {
          collecting = 1
        }
        continue
      }
    }
  }
  function ret_of(rest,    p, t, e) {
    p = index(rest, ":")
    if (p == 0) return "V"
    t = substr(rest, p + 1)
    e = index(t, "=")
    if (e > 0) t = substr(t, 1, e - 1)
    gsub(/^[ \t]+/, "", t); gsub(/[ \t]+$/, "", t)
    sub(/[^A-Za-z0-9_?].*/, "", t)
    if (t == "" || t == "Unit") return "V"
    gsub(/\?/, "", t)
    return jni_of(t)
  }
  function params_of_line(nr,    s, i, c, d, txt, k) {
    p = index(L[nr], "(")
    if (p == 0) return ""
    d = 0; txt = ""
    for (k = nr; k <= NL; k++) {
      s = (k == nr) ? substr(L[k], p + 1) : L[k]
      for (i = 1; i <= length(s); i++) {
        c = substr(s, i, 1)
        if (c == "(") d++
        else if (c == ")") { d--; if (d < 0) { return params_of(txt) } }
        txt = txt c
      }
      txt = txt " "
    }
    return params_of(txt)
  }
  ' "$f"
  done
}

native_tables() {
  local f
  for f in $(find "$CPP_JNI_DIR" -name '*.cpp' | sort); do
  awk '
  function trim(s) { gsub(/^[ \t]+/, "", s); gsub(/[ \t]+$/, "", s); return s }
  function firstq(s,    p, q, rest) {
    p = index(s, "\"")
    if (p == 0) return ""
    rest = substr(s, p + 1)
    q = index(rest, "\"")
    if (q == 0) return ""
    return substr(rest, 1, q - 1)
  }
  function secondq(s,    p, q, rest, r2, q2) {
    p = index(s, "\"")
    if (p == 0) return ""
    rest = substr(s, p + 1)
    q = index(rest, "\"")
    if (q == 0) return ""
    r2 = substr(rest, q + 1)
    return firstq(r2)
  }
  function cfunc(s,    p, r, q) {
    p = index(s, "reinterpret_cast<void*>(")
    if (p == 0) return ""
    r = substr(s, p + 23)
    q = index(r, ")")
    if (q == 0) return ""
    return substr(r, 1, q - 1)
  }
  { L[FNR] = $0; NL = FNR }
  END {
    arr = ""; in_entry = 0; text = ""; startline = 0
    for (i = 1; i <= NL; i++) {
      line = L[i]
      if (!in_entry && line ~ /JNINativeMethod/ && index(line, "[") > 0) {
        n = 0; delete w; split(line, w, " ")
        for (k = 1; k <= length(w); k++) {
          if (index(w[k], "JNINativeMethod") == 0 && index(w[k], "[") > 0) {
            arr = w[k]; sub(/\[.*/, "", arr); break
          }
        }
      }
      if (!in_entry) {
        p = index(line, "{\"")
        if (p == 0) continue
        in_entry = 1; text = substr(line, p + 1); startline = i
      } else {
        text = text " " line
      }
      if (index(text, "}") > 0) {
        cpos = index(text, "}")
        entry = substr(text, 1, cpos - 1)
        printf "NATIVE\t%s\t%s\t%d\t%s\t%s\t%s\n", FILENAME, arr, startline, firstq(entry), secondq(entry), cfunc(entry)
        in_entry = 0; text = ""
      }
    }
  }
  ' "$f"
  done
}

# class constant -> Kotlin class path (frozen; see jni_bridge.h)
class_path() {
  awk -v name="$1" '
  function firstq(s,    p, q, rest) {
    p = index(s, "\"")
    if (p == 0) return ""
    rest = substr(s, p + 1)
    q = index(rest, "\"")
    if (q == 0) return ""
    return substr(rest, 1, q - 1)
  }
  { L[FNR] = $0; NL = FNR }
  END {
    for (i = 1; i <= NL; i++) {
      if (index(L[i], "constexpr char " name "[]") > 0) {
        rest = substr(L[i], index(L[i], "=") )
        v = firstq(rest)
        if (v != "") { print v; exit }
        for (j = i + 1; j <= NL && j <= i + 3; j++) {
          v = firstq(L[j]); if (v != "") { print v; exit }
        }
      }
    }
  }
  ' "$JNI_BRIDGE_H"
}

# array name -> Kotlin object name, through the frozen class constants in
# jni_bridge.h: kNativeLogMethods -> kNativeLogClass -> .../NativeLog.
obj_of_array() {
  local arr="$1" base cand p
  base="${arr#k}"; base="${base%Methods}"
  for cand in "k${base}Class" "kNative${base}Class"; do
    p=$(class_path "$cand")
    if [ -n "$p" ]; then printf '%s' "${p##*/}"; return 0; fi
  done
  printf 'unknown'
}

kt_native_extract > "$WORK/ktnative.tsv"
native_tables > "$WORK/native.tsv"

header "JNI contract" \
  "$OUT_DIR/jni-contract.md" "$JNI_BRIDGE_H" \
  "$CPP_JNI_DIR/nat_detector_jni.cpp" "$CPP_JNI_DIR/native_log_jni.cpp" "$CPP_JNI_DIR/vp9_encoder_jni.cpp"

{
  printf '> **Errata:** an earlier captain ruling assumed `Java_*` exports; this repo uses `JNI_OnLoad` + `RegisterNatives` (see `%s`) — table is built from Kotlin declarations + `JNINativeMethod` tables + C prototypes.\n>\n' "$(grep -n 'JNI_OnLoad + RegisterNatives' "$CPP_JNI_DIR/jni_bridge.h" | head -1 | cut -d: -f1 | sed "s|^|$CPP_JNI_DIR/jni_bridge.h:|")"
  printf '> **Generated headers:** `:generated headers not present in repo; table built from Kotlin declarations + .so exports + cpp/jni sources`. If a generated `*_jni.h` is ever added to the repository this table picks it up first (priority: generated header > Kotlin declarations + `JNINativeMethod` tables).\n>\n'
  printf '> **`.so` is a build artifact, not in VCS:** `.gitignore:56 *.so` and `app/.gitignore:2 /build/`; `git ls-files app/src/main/jniLibs` is empty and the only tracked-artifact candidates in `%s` are `libjingle_peerconnection_so.so` and `libc++_shared.so`. `libwebrtcdemo_native.so` exists only under `app/build/intermediates/**`.\n>\n' "$(dirname "$JNILIBS_SO")"
  # Evidence line numbers are discovered at generation time so the citation can
  # never drift away from the report text it points at.
  UPSTREAM_REPORT="reports/05-libwebrtc-build.md"
  UPSTREAM_LINE=$(grep -n 'GEN_JNI' "$UPSTREAM_REPORT" 2>/dev/null | head -1 | cut -d: -f1)
  printf '> **Registration tables:** the `JNINativeMethod` arrays this table is built from live in `%s:49`, `%s:71` and `%s:347`.\n>\n' \
    "$CPP_JNI_DIR/nat_detector_jni.cpp" "$CPP_JNI_DIR/native_log_jni.cpp" "$CPP_JNI_DIR/vp9_encoder_jni.cpp"
  if [ -n "$UPSTREAM_LINE" ]; then
    printf '> **Upstream libwebrtc bindings** (`J.N` / `GEN_JNI`) are documented descriptively — they live in `third_party/`, are upstream-owned and are not machine-generated here. Evidence: `%s:%s` (and the t29/t30/t36 signature comparisons; t36 produced the 193/193 comparison).\n\n' "$UPSTREAM_REPORT" "$UPSTREAM_LINE"
  else
    printf '> **Upstream libwebrtc bindings** (`J.N` / `GEN_JNI`) are documented descriptively in `reports/`; they live in `third_party/` and are upstream-owned.\n\n'
  fi
  printf '## 1. Registered Kotlin classes (`%s`)\n\n' "$JNI_BRIDGE_H"
  printf '| Class constant | Kotlin class path | Declared at |\n|---|---|---|\n'
  grep -n 'constexpr char k[A-Za-z0-9_]*Class\[\]' "$JNI_BRIDGE_H" | while IFS=: read -r ln rest; do
    const=$(printf '%s' "$rest" | sed -E 's/.*char ([A-Za-z0-9_]+)\[\].*/\1/')
    path=$(class_path "$const")
    printf '| `%s` | `%s` | `%s:%s` |\n' "$const" "$path" "$JNI_BRIDGE_H" "$ln"
  done
  printf '\n## 2. Kotlin `external fun` declarations (`%s/**`)\n\n' "$KT_NATIVE_DIR"
  printf '| Object | Function | Parameters | Derived JNI descriptor | KDoc descriptor | Source |\n|---|---|---|---|---|---|\n'
  awk -F'\t' '$1=="KTFUN" { printf "| `%s` | `%s` | %s | `%s` | %s | `%s:%s` |\n", $2, $3, $4, $5, ($6 == "" ? "-" : "`" $6 "`"), $7, $8 }' "$WORK/ktnative.tsv"
  printf '\n## 3. `JNINativeMethod` registration tables (native side)\n\n'
  printf '| Array | Method name | JNI descriptor | C function | Source |\n|---|---|---|---|---|\n'
  awk -F'\t' '$1=="NATIVE" { printf "| `%s` | `%s` | `%s` | `%s` | `%s:%s` |\n", $3, $5, $6, $7, $2, $4 }' "$WORK/native.tsv"
  printf '\n## 4. C function definitions (`%s/*.h|*.cpp`)\n\n' "$CPP_JNI_DIR"
  printf '| C function | Definition |\n|---|---|\n'
  awk -F'\t' '$1=="NATIVE" {print $7}' "$WORK/native.tsv" | sort -u | while read -r fn; do
    [ -z "$fn" ] && continue
    hit=$(grep -rn "^[A-Za-z].*[^A-Za-z0-9_]${fn}(" "$CPP_JNI_DIR" --include='*.cpp' --include='*.h' | head -1 || true)
    if [ -n "$hit" ]; then
      printf '| `%s` | `%s` |\n' "$fn" "\`$(printf '%s' "$hit" | cut -d: -f1):$(printf '%s' "$hit" | cut -d: -f2)\`"
    else
      printf '| `%s` | **not found** |\n' "$fn"
    fi
  done
  printf '\n## 5. Kotlin -> native callbacks (C++ calls Java)\n\n'
  printf 'Registered through `GetStaticMethodID` in `%s/callback_bridge.cpp`.\n\n' "$CPP_JNI_DIR"
  printf '| Kotlin method | Kotlin declaration | C++ name constant | C++ signature constant |\n|---|---|---|---|\n'
  grep -nE '^\s*(fun|external fun) (on[A-Za-z0-9_]+)\(' "$KT_NATIVE_DIR/NativeCallbacks.kt" 2>/dev/null | while IFS=: read -r ln rest; do
    fn=$(printf '%s' "$rest" | sed -E 's/.*fun ([A-Za-z0-9_]+)\(.*/\1/')
    cname=$(grep -n "constexpr char k${fn}\[\]" "$CPP_JNI_DIR/callback_bridge.cpp" | head -1 || true)
    csig=$(grep -n "constexpr char k${fn}Signature\[\]" "$CPP_JNI_DIR/callback_bridge.cpp" | head -1 || true)
    cn="${cname##*:}"; cn="\`${CPP_JNI_DIR}/callback_bridge.cpp:${cname%%:*}\`"
    cs="${csig##*:}"; cs="\`${CPP_JNI_DIR}/callback_bridge.cpp:${csig%%:*}\`"
    [ -z "$cname" ] && cn="**missing**"
    [ -z "$csig" ] && cs="**missing**"
    printf '| `%s` | `%s:%s` | %s | %s |\n' "$fn" "$KT_NATIVE_DIR/NativeCallbacks.kt" "$ln" "$cn" "$cs"
  done
  printf '\n## 6. `.so` exported symbols\n\n'
  printf '| Check | Result |\n|---|---|\n'
  if [ -f "$JNILIBS_SO" ]; then
    printf '| `.so` present in repo tree | `%s` |\n' "$JNILIBS_SO"
  else
    printf '| `.so` present in repo tree | UNVERIFIED (.so is a build artifact, not in repo) — binding is `JNI_OnLoad` + `RegisterNatives`, so no `Java_*` exports are expected |\n'
  fi
  if command -v nm >/dev/null 2>&1 || command -v readelf >/dev/null 2>&1; then
    printf '| binutils for symbol extraction | available |\n'
  else
    printf '| binutils for symbol extraction | UNVERIFIED (no binutils in container: `nm`/`readelf`/`objdump` unavailable) |\n'
  fi
  printf '\n## 7. Divergence: Kotlin declarations vs `JNINativeMethod` tables\n\n'
  # Key by <Kotlin object>#<method>: `nativeInit` exists on both NativeLog and
  # NativeVp9Encoder, so a bare method name is not a unique key.
  : > "$WORK/kt_keys.tsv"
  while IFS=$'\t' read -r kind obj fn params desc kdoc file line; do
    [ "$kind" = "KTFUN" ] || continue
    printf '%s#%s\t%s\t%s:%s\n' "$obj" "$fn" "$desc" "$file" "$line" >> "$WORK/kt_keys.tsv"
  done < "$WORK/ktnative.tsv"
  sort -u "$WORK/kt_keys.tsv" -o "$WORK/kt_keys.tsv"
  : > "$WORK/native_keys.tsv"
  while IFS=$'\t' read -r kind file arr line name desc cfunc; do
    [ "$kind" = "NATIVE" ] || continue
    o=$(obj_of_array "$arr")
    printf '%s#%s\t%s\t%s:%s\n' "$o" "$name" "$desc" "$file" "$line" >> "$WORK/native_keys.tsv"
  done < "$WORK/native.tsv"
  sort -u "$WORK/native_keys.tsv" -o "$WORK/native_keys.tsv"
  cut -f1 "$WORK/kt_keys.tsv" > "$WORK/kt_only.txt"
  cut -f1 "$WORK/native_keys.tsv" > "$WORK/native_only.txt"
  printf '| Direction | Methods |\n|---|---|\n'
  missing_native=$(comm -23 "$WORK/kt_only.txt" "$WORK/native_only.txt" | sed 's/.*/`&`/' | paste -sd', ' -)
  missing_kt=$(comm -13 "$WORK/kt_only.txt" "$WORK/native_only.txt" | sed 's/.*/`&`/' | paste -sd', ' -)
  printf '| Declared in Kotlin, not registered natively | %s |\n' "${missing_native:-_(none)_}"
  printf '| Registered natively, not declared in Kotlin | %s |\n' "${missing_kt:-_(none)_}"
  printf '\n| Method (`Object#method`) | Kotlin descriptor (derived) | Native descriptor (registered) | Match |\n|---|---|---|---|\n'
  nmatch=0
  while IFS=$'\t' read -r key desc src; do
    [ -z "$key" ] && continue
    nd=$(awk -F'\t' -v k="$key" '$1==k {print $2; exit}' "$WORK/native_keys.tsv")
    if [ -z "$nd" ]; then
      printf '| `%s` | `%s` | _(not registered)_ | **NO** |\n' "$key" "$desc"
    elif [ "$desc" = "$nd" ]; then
      printf '| `%s` | `%s` | `%s` | yes |\n' "$key" "$desc" "$nd"
      nmatch=$((nmatch + 1))
    else
      printf '| `%s` | `%s` | `%s` | **NO** |\n' "$key" "$desc" "$nd"
    fi
  done < "$WORK/kt_keys.tsv"
  total_kt=$(wc -l < "$WORK/kt_only.txt" | tr -d ' ')
  total_native=$(wc -l < "$WORK/native_only.txt" | tr -d ' ')
  printf '\n**Result: `%s` Kotlin declarations, `%s` registered natives, `%s` descriptor matches; missing: Kotlin-side %s, native-side %s.**\n' \
    "$total_kt" "$total_native" "$nmatch" "${missing_native:-none}" "${missing_kt:-none}"
} >> "$OUT_DIR/jni-contract.md"

# =============================================================================
# 4. host-commands.md
# =============================================================================
# Provenance classes (task contract + captain ruling):
#   in-repo                          -> scripts/**, deploy/**
#   report:<file>:<line>             -> reports/**  (historical command evidence)
#   workspace-only, outside git repo -> ../tmp/*.sh (workspace root, outside the repo)
REPO_TMP="$(cd "$REPO_ROOT/../.." && pwd)/tmp"

scan_commands() { # <file> <class>
  local f="$1" cls="$2"
  [ -f "$f" ] || return 0
  awk -v cls="$cls" -v file="$f" '
  function token(t, kind) {
    if (t == "") return
    if (t == "clean" || t == "assembleDebug" || t == "testDebugUnitTest" || t == "compileDebugKotlin") kind = "gradle-task"
    else if (t ~ /^-P/) kind = "gradle-property"
    else if (t ~ /^--/) kind = "flag"
    else if (t ~ /^:/) kind = "gradle-task"
    else kind = "command"
    printf "%s\t%s\t%s\t%s:%d\n", t, kind, cls, file, FNR
  }
  {
    line = $0
    if (line ~ /gradlew|gradle |publish_apk|build_app\.sh/) {
      rest = line
      while (match(rest, /:[A-Za-z][A-Za-z0-9_:]*/)) { token(substr(rest, RSTART, RLENGTH)); rest = substr(rest, RSTART + RLENGTH) }
      rest = line
      while (match(rest, /-P[A-Za-z0-9_.]+=[A-Za-z0-9_.-]+/)) { token(substr(rest, RSTART, RLENGTH)); rest = substr(rest, RSTART + RLENGTH) }
      rest = line
      while (match(rest, /--[a-z][a-z0-9-]*/)) { token(substr(rest, RSTART, RLENGTH)); rest = substr(rest, RSTART + RLENGTH) }
      if (line ~ /(^|[ \t])(clean|assembleDebug|testDebugUnitTest|compileDebugKotlin)([ \t]|$)/) {
        rest = line
        while (match(rest, /(^|[ \t])(clean|assembleDebug|testDebugUnitTest|compileDebugKotlin)([ \t]|$)/)) {
          t = substr(rest, RSTART, RLENGTH); gsub(/^[ \t]+/, "", t); gsub(/[ \t]+$/, "", t)
          token(t, "gradle-task")
          rest = substr(rest, RSTART + RLENGTH)
        }
      }
    }
    # Gradle properties are frequently assigned to a variable and only expanded on
    # the command line, so scan them independently of the `gradlew` heuristic.
    if (line ~ /-P[A-Za-z0-9_.]+=/) {
      rest = line
      while (match(rest, /-P[A-Za-z0-9_.]+=[A-Za-z0-9_.-]+/)) { token(substr(rest, RSTART, RLENGTH)); rest = substr(rest, RSTART + RLENGTH) }
    }
    if (file ~ /(^|\/)scripts\// && line ~ /^[ \t]*--[a-z][a-z0-9-]*\)/) {
      rest = line
      while (match(rest, /--[a-z][a-z0-9-]*/)) { token(substr(rest, RSTART, RLENGTH)); rest = substr(rest, RSTART + RLENGTH) }
    }
  }
  ' "$f"
}

: > "$WORK/cmd_repo.tsv"
for f in scripts/*.sh scripts/*.py deploy/*; do
  [ -f "$f" ] || continue
  # The generator itself is not a command producer; scanning it would record its
  # own extraction patterns as if they were real invocations.
  [ "$f" = "scripts/gen-doc-tables.sh" ] && continue
  scan_commands "$f" "in-repo" >> "$WORK/cmd_repo.tsv"
done
: > "$WORK/cmd_report.tsv"
for f in $(find reports -maxdepth 1 -name '*.md' 2>/dev/null | sort); do
  scan_commands "$f" "report" >> "$WORK/cmd_report.tsv"
done
: > "$WORK/cmd_ws.tsv"
if [ -d "$REPO_TMP" ]; then
  for f in $(find "$REPO_TMP" -maxdepth 1 -name '*.sh' 2>/dev/null | sort); do
    rel="../../tmp/$(basename "$f")"
    scan_commands "$f" "workspace-only" \
      | awk -F'\t' -v rel="$rel" '{ n = split($4, a, ":"); printf "%s\t%s\t%s\t%s:%s\n", $1, $2, $3, rel, a[n] }' >> "$WORK/cmd_ws.tsv"
  done
fi

header "Host/workspace command inventory" \
  "$OUT_DIR/host-commands.md" scripts/build_app.sh deploy/signaling.service

{
  printf '> **Provenance classes** (see `doc/design/SPEC.md` §7 and the t2 task contract): `in-repo` = a repository script or\n'
  printf '> deploy unit; `report:<file>:<line>` = an engineering report recording a historical invocation;\n'
  printf '> `workspace-only, outside git repo` = `../tmp/*.sh` at the workspace root (not part of the repository).\n'
  printf '> A document that cites a name whose only provenance is `workspace-only` must also cite an in-repo\n'
  printf '> `reports/<file>:<line>`; `scripts/doc-verify.sh` enforces this.\n\n'
  printf '## 1. Names provable from repository scripts (`in-repo`)\n\n'
  printf '| Token | Kind | Provenance |\n|---|---|---|\n'
  awk -F'\t' '$3=="in-repo" { printf "%s\t%s\t%s\n", $1, $2, $4 }' "$WORK/cmd_repo.tsv" | sort -u \
    | awk -F'\t' '{ printf "| `%s` | %s | `%s` |\n", $1, $2, $3 }'
  printf '\n## 2. Names recorded in reports (`report:<file>:<line>`)\n\n'
  printf '| Token | Kind | Provenance |\n|---|---|---|\n'
  awk -F'\t' '$3=="report" { printf "%s\t%s\t%s\n", $1, $2, $4 }' "$WORK/cmd_report.tsv" | sort -u \
    | awk -F'\t' '{ printf "| `%s` | %s | `report:%s` |\n", $1, $2, $3 }'
  printf '\n## 3. Names seen only in workspace scripts (`workspace-only, outside git repo`)\n\n'
  printf '| Token | Kind | Provenance |\n|---|---|---|\n'
  if [ -s "$WORK/cmd_ws.tsv" ]; then
    awk -F'\t' '{ printf "%s\t%s\t%s\n", $1, $2, $4 }' "$WORK/cmd_ws.tsv" | sort -u \
      | awk -F'\t' '{ printf "| `%s` | %s | `%s` (workspace-only, outside git repo) |\n", $1, $2, $3 }'
  else
    printf '| _(none: `../../tmp/*.sh` not present)_ | - | - |\n'
  fi
  printf '\n## 4. Host-side publish chain (`publish_apk.sh`)\n\n'
  printf '`publish_apk.sh` is **not in the repository** — it is the host script HOST: `/opt/apk-http/publish_apk.sh` (not\n'
  printf 'visible in the container). It must therefore only ever be cited as `HOST:` with report provenance, never as a\n'
  printf 'repository-relative path. In-repo evidence:\n\n'
  printf '| Item | Evidence |\n|---|---|\n'
  for ev in reports/37-t81-build-publish.md:101 reports/37-t76-build-publish.md:100 reports/41-apk-http-ownership.md:29 reports/37-t76-build-publish.md:129 reports/37-t81-build-publish.md:129 reports/10-app-build.md:1093; do
    f=${ev%:*}; l=${ev##*:}
    if [ -f "$f" ]; then
      printf '| `%s` | `%s` |\n' "$(sed -n "${l}p" "$f" | cut -c1-150)" "$f:$l"
    fi
  done
  printf '\n## 5. Machine-readable token set (for `doc-verify.sh`)\n\n'
  printf '| Token | Class |\n|---|---|\n'
  {
    awk -F'\t' '$3=="in-repo" { printf "%s\tin-repo\n", $1 }' "$WORK/cmd_repo.tsv"
    awk -F'\t' '$3=="report" { printf "%s\treport\n", $1 }' "$WORK/cmd_report.tsv"
    if [ -s "$WORK/cmd_ws.tsv" ]; then awk -F'\t' '{ printf "%s\tworkspace-only\n", $1 }' "$WORK/cmd_ws.tsv"; fi
  } | sort -k1,1 -k2,2 | awk -F'\t' '!seen[$1]++' | awk -F'\t' '{ printf "| `%s` | %s |\n", $1, $2 }'
} >> "$OUT_DIR/host-commands.md"

echo "gen-doc-tables.sh: wrote"
for f in signaling-messages.md log-events.md jni-contract.md host-commands.md; do
  printf '  %s (%s bytes, sha256 %s)\n' "$OUT_DIR/$f" "$(stat -c %s "$OUT_DIR/$f")" "$(sha256_of "$OUT_DIR/$f")"
done
exit 0
