#!/bin/bash
# Regenerates tools/golden_vectors.txt from a real AriaCoda build.
#
# The checked-in golden_vectors.txt is the output of this script, so the test
# suite runs without AriaCoda. Re-run this only to re-verify against upstream or
# to add new commands.
#
# Note: this tool links libAria and so falls under AriaCoda's GPL-2.0 terms. It
# is a development aid and is not part of the library. See NOTICE.md.
set -e
cd "$(dirname "$0")"

ARIA="${ARIA:-$PWD/AriaCoda}"
if [ ! -d "$ARIA" ]; then
  echo "Cloning AriaCoda into $ARIA"
  git clone --depth 1 https://github.com/reedhedges/AriaCoda.git "$ARIA"
fi
if [ ! -f "$ARIA/lib/libAria.so" ]; then
  echo "Building AriaCoda (a few minutes)"
  (cd "$ARIA" && make -j"$(nproc)")
fi

g++ -std=c++17 -o /tmp/arcos_golden golden_vectors.cpp \
    -I"$ARIA/include" -L"$ARIA/lib" -lAria -lpthread -ldl

LD_LIBRARY_PATH="$ARIA/lib" /tmp/arcos_golden > golden_vectors.txt
echo "Wrote $(wc -l < golden_vectors.txt) vectors to golden_vectors.txt"
