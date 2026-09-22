#!/usr/bin/env bash
set -euo pipefail

# Lints docs/topics/supported-features.md, the catalogue of Kotlin <-> C#
# mappings. The page is an index, not a manual: every row is five cells
# (Kotlin | direction | C# | Notes | Docs), the Notes cell is one short
# clause, and the long-form explanation lives in the linked topic page. The
# ADRs behind a mapping are indexed in docs/adr/README.md, not on the page.
# This script is what keeps that shape, and the Docs CI runs it.
#
# Portability: the whole pass is one awk program, written for mawk (the awk on
# ubuntu-latest) as well as the gawk or busybox awk on a dev box. No gensub, no
# three-argument match, no interval regexes, and cells are split on a private
# control byte rather than on "|", which a single-character separator argument
# would leave open to being read as an empty regex alternation.
#
# Character counting: mawk's length() counts bytes, so the Notes cell is
# measured on a copy with the UTF-8 continuation bytes removed
# (gsub(/[\200-\277]/, "")) under LC_ALL=C, which leaves exactly one byte per
# character. Cells 1 to 3 and the direction glyph are compared as raw bytes.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

FILE="docs/topics/supported-features.md"

if [ ! -f "$FILE" ]; then
  echo "$FILE: file not found"
  echo "features check FAILED (1 violation)"
  exit 1
fi

TOPIC_FILES="$(ls docs/topics)"

LC_ALL=C awk \
  -v file="$FILE" \
  -v topic_files="$TOPIC_FILES" '
function trim(s) {
  gsub(/^[ \t]+/, "", s)
  gsub(/[ \t]+$/, "", s)
  return s
}
function err(msg) {
  printf "%s:%d: %s\n", file, FNR, msg
  bad++
}
BEGIN {
  n = split(topic_files, tf, "\n")
  for (i = 1; i <= n; i++) if (tf[i] != "") topic[tf[i]] = 1
  esc = sprintf("%c", 1)
  bar = sprintf("%c", 2)
  bs = sprintf("%c", 92)
  mode = 0
  rows = 0
  bad = 0
}
{
  line = $0

  # Checks that apply to every line of the page, table or prose.
  if (line ~ /^\*\*Notes\*\*/) err("per-table **Notes** footer block; fold it into the rows")
  if (line ~ /^- \*\*`\[/) err("footnote list item; fold it into the row it belongs to")
  if (line ~ /^\*\*`\[/) err("footnote paragraph; fold it into the row it belongs to")
  if (index(line, "[^") > 0) err("markdown footnote reference; the page carries no footnotes")
  if (index(line, "> [!") > 0) err("GitHub admonition; Writerside needs a <note><p>...</p></note> block")
  if (line ~ /\]\(docs\/adr\//) err("relative docs/adr link; Writerside needs the absolute GitHub URL")
  if (line ~ /\]\(docs\/topics\//) err("relative docs/topics link; link the bare file name instead")

  if (line == "") { mode = 0; next }
  if (substr(line, 1, 1) != "|") next

  # A separator row is nothing but pipes, dashes, colons and blanks.
  sep = line
  gsub(/[|:\- \t]/, "", sep)
  if (sep == "") next

  # Split into cells, holding escaped pipes aside so they never split a row.
  raw = line
  gsub(/\\\|/, esc, raw)
  sub(/^\|/, "", raw)
  sub(/\|[ \t]*$/, "", raw)
  gsub(/\|/, bar, raw)
  nc = split(raw, cell, bar)
  for (i = 1; i <= nc; i++) {
    cell[i] = trim(cell[i])
    gsub(esc, bs "|", cell[i])
  }

  if (cell[1] == "Kotlin") { mode = 6; next }
  if (cell[1] == "Glyph") { mode = 2; next }
  if (mode == 2) next
  if (mode == 0) {
    err("table row outside a table with a recognised header row")
    next
  }

  rows++

  # 2. Every body row is exactly five cells.
  if (nc != 5) {
    err("row has " nc " cells, expected 5 (Kotlin | direction | C# | Notes | Docs)")
    next
  }

  # 3. The direction cell is one of the four legend glyphs.
  if (cell[2] != "\342\206\222" && cell[2] != "\342\206\220" && cell[2] != "\342\207\204" && cell[2] != "\342\207\270")
    err("direction cell is \"" cell[2] "\", expected one of the four legend glyphs")

  # 4. Notes is one short clause and carries no footnote marker.
  notes = cell[4]
  measured = notes
  gsub(/[\200-\277]/, "", measured)
  if (length(measured) > 200)
    err("Notes is " length(measured) " characters (max 200)")
  if (notes ~ /\[[0-9]+\]/)
    err("Notes carries a [n] footnote marker; say the thing or drop it")

  # 5. Docs is empty, or topic-page links joined by a middle dot.
  docs = cell[5]
  if (docs != "") {
    nd = split(docs, dl, " \302\267 ")
    for (i = 1; i <= nd; i++) {
      link = trim(dl[i])
      if (link !~ /^\[[^][]+\]\([A-Za-z0-9._-]+\.md\)$/) {
        err("Docs entry \"" link "\" is not a [text](name.md) link")
        continue
      }
      name = link
      sub(/^\[[^][]*\]\(/, "", name)
      sub(/\)$/, "", name)
      if (!(name in topic))
        err("Docs links docs/topics/" name ", which does not exist")
    }
  }

  # 6. No two rows describe the same mapping.
  key = cell[1] SUBSEP cell[2] SUBSEP cell[3]
  if (key in seen)
    err("duplicate row: \"" cell[1] "\" " cell[2] " \"" cell[3] "\" already appears on line " seen[key])
  else
    seen[key] = FNR
}
END {
  if (bad > 0) {
    printf "features check FAILED (%d violation%s across %d rows)\n", bad, (bad == 1 ? "" : "s"), rows
    exit 1
  }
  printf "features check OK (%d rows)\n", rows
}
' "$FILE"
