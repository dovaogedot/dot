#!/bin/sh
# Runs every end-to-end suite against the installed binary (override with DOT_BIN=<path>).
set -eu
dir="$(cd "$(dirname "$0")" && pwd)"
for suite in conflict park; do
  echo "== $suite"
  sh "$dir/$suite.sh" > /dev/null || { echo "FAIL: $suite"; exit 1; }
done
echo "all suites passed"
