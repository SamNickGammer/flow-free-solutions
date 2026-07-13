#!/usr/bin/env bash
# Build the handwritten-style research notes PDF.
#   ./tools/build_notes.sh     (run from repo root)
#
# 1. solve every variant board and dump verified JSON
# 2. inline the JSON into the notes template
# 3. render to PDF with headless Chrome
set -euo pipefail
cd "$(dirname "$0")/.."

CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
[ -x "$CHROME" ] || CHROME="/Applications/Brave Browser.app/Contents/MacOS/Brave Browser"
[ -x "$CHROME" ] || { echo "need Chrome or Brave to render the PDF"; exit 1; }

mkdir -p notes
echo "→ solving boards…"
( cd tools && python3 gen_notes_data.py boards.json )

echo "→ building HTML…"
python3 - <<'EOF'
import pathlib
tpl  = pathlib.Path('tools/notes.html').read_text()
data = pathlib.Path('tools/boards.json').read_text()
pathlib.Path('notes/flow-free-notes.html').write_text(tpl.replace('__BOARDS__', data))
EOF

echo "→ rendering PDF…"
"$CHROME" --headless --disable-gpu --no-pdf-header-footer \
  --virtual-time-budget=8000 \
  --print-to-pdf="$PWD/notes/flow-free-notes.pdf" \
  "file://$PWD/notes/flow-free-notes.html" 2>/dev/null

echo "✓ notes/flow-free-notes.pdf"
