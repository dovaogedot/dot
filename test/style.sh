#!/bin/sh
# Style gate: an if whose then-branch sits inline on the if line must keep its
# else on the same line; a multi-line if puts then at end of line with each
# branch below.
set -eu

dir="$(cd "$(dirname "$0")/.." && pwd)"
bad=0
for f in "$dir"/Main.scala "$dir"/src/*.scala "$dir"/src/commands/*.scala "$dir"/scripts/*.scala; do
  awk '
    /^[[:space:]]*else([[:space:]]|$)/ && prev_inline {
      print FILENAME ":" FNR ": else on a new line after an inline if-then"
      bad = 1
    }
    {
      prev_inline = 0
      if ($0 ~ /(^|[^A-Za-z0-9_])if[[:space:]]/ && match($0, /[[:space:]]then[[:space:]]+[^[:space:]]/)) {
        rest = substr($0, RSTART + RLENGTH - 1)
        if (rest !~ /(^|[[:space:]])else([[:space:]]|$)/) prev_inline = 1
      }
    }
    END { exit bad }
  ' "$f" || bad=1
done
[ "$bad" = 0 ] || { echo 'FAIL: asymmetric if/else layouts found'; exit 1; }
echo 'ok: no asymmetric if/else layouts'
