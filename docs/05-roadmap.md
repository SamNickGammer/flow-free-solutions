# 05 — Roadmap

Phased so each phase ships something runnable and testable on its own. Solver-first, mobile last —
the risky CV/Android work rides on top of a proven engine.

## Phase 1 — Solver core (desktop CLI)

**Build:** the `:solver` module (**graph** model + DFS search + pruning) and a CLI that reads a
text grid and prints the solution.

- Graph model: `nodes` / `edges` / `bridges` — **not** a `[r][c]` grid. See
  [04-solver-design.md](04-solver-design.md).
- DFS with most-constrained-color, forced-move propagation, `prune_ok` (reachability + coverage +
  stranded-pocket + deadend).
- Text format parser incl. `WALL` / `HOLE` / `BRIDGE` directives
  ([levels/README.md](levels/README.md)).
- Fixtures + self-check (3×3, several 5×5).

> **Build the graph model now, not later.** A cell-based `owner[r][c]` solver cannot represent a
> bridge and computes neighbors with `r±1` arithmetic that silently ignores walls and seams.
> Retrofitting it means rewriting every flood fill. This is the one place where doing it right
> up front is *less* work, not more.

**Done when:** `flowsolve puzzle.txt` solves all fixtures with valid, full-coverage output, and
the self-check passes. No Android, no CV yet.

## Phase 2 — Level variants

**Build:** the parse-time transforms. If Phase 1's graph model is right, **this is mostly parser
work, not solver work.**

- **Walls** — remove edges. **Obstacles** — remove nodes. **Rectangle** — `R ≠ C`. All should be
  ~free; if any of them requires touching the search loop, Phase 1's model is wrong — fix that
  instead of special-casing here.
- **Bridges** — the one real solver change: dual-color crossover nodes, straight-through
  traversal, dual coverage. Import the 5×5 Bridges 1–30 corpus.
- **Mania** — the scale test. Add 12×12/14×14 fixtures with a **time bound**, not just a
  correctness assert.
- **Cubes / Links** — confirm the actual mechanic from a screenshot first
  ([levels/06-links.md](levels/06-links.md), [levels/07-cubes.md](levels/07-cubes.md)); both are
  expected to reduce to add-edges / remove-nodes.

Per-variant fixtures and what each must prove: **[levels/](levels/)**.

**Done when:** every variant fixture solves, and the load-bearing negative tests pass (drop a wall
→ unsolvable; drop a seam → unsolvable; count a hole as coverable → unsolvable).

## Phase 3 — Grid detection (screenshot → model)

**Build:** `:detection` — bitmap in, puzzle model out. **This is the hard phase**, and the
variants are why: the solver treats them all identically, but the *detector* has to tell them
apart.

- Locate board bounds + cell size; sample cell centers (median of a patch, not one pixel).
- **Cell classification** (3-way): empty / endpoint color / **hole**.
- **Border classification**: **wall** vs ordinary gridline — discriminate on thickness+brightness.
  The most error-prone step in the project; a single missed wall makes a board unsolvable with no
  obvious clue why.
- **Bridge glyph** detection.
- First version: **manual grid confirm/nudge** (calibration knob), then automate.
- Cubes detection is a **stretch goal** — support Cubes via manual text entry first.

> Detection is where the wall/obstacle distinction actually bites. Getting it wrong produces
> "every board is unsolvable" — a symptom that points nowhere near its cause. Build the
> misclassification negative tests early.

**Done when:** feeding a folder of saved Flow Free screenshots (incl. walls, holes, bridges)
produces models the Phase 1/2 solver solves correctly.

## Phase 4 — Mobile shell + ✋ Manual mode (Android)

**Build:** `:app`, `:capture`, `:overlay`. Spec: **[06-ux.md](06-ux.md)**.

- Foreground service + persistent notification (stay alive in background).
- Floating bubble (`SYSTEM_ALERT_WINDOW`).
- MediaProjection one-frame capture on tap.
- Wire capture → detection → solver → **hold the solution, draw nothing**.
- The **choice card**: "Solved — 4 flows" → `✋ Manual` / `⚡ Draw it`.
- **Manual overlay**: faint paths (~35% opacity), **direction arrows**, pulsing start dots.

> **Solve silently, then ask.** Nothing is drawn on the board until the user picks a mode.

> ⚠️ **The overlay window MUST be `FLAG_NOT_TOUCHABLE`.** Otherwise it swallows the user's finger and
> they cannot trace the answer it is showing them — the app looks perfect in a screenshot and is
> unusable in the hand. Only the bubble takes touches.

**Done when:** open a level, tap the bubble, pick Manual, and trace the arrows to complete the level.
No Accessibility permission needed for any of this.

## Phase 5 — ⚡ Draw it (auto-draw)

**Build:** `:autodraw` — `AccessibilityService.dispatchGesture()`. Confirmed viable: Android
synthesises real touch events; the game can't tell them from a finger. No root.

- Map solved cell paths → screen-pixel `Path` → **one stroke per flow**, dispatched **in sequence**
  (visibly one-by-one).
- **Interpolate per cell**, not just at corners, or long straight runs skip cells.
- **Stroke-speed setting** (start ~40–60 ms/cell). Real knob, user-visible — too fast and the game
  drops cells.
- Calibration offset so strokes land on-cell.
- **Confidence gate**: only fire auto-draw on a board the detector read cleanly (the parity
  pre-check from [04-solver-design.md](04-solver-design.md) is a cheap gate).

> ⚠️ **Auto-draw is destructive when wrong.** A few pixels of calibration error starts a swipe on
> the wrong cell, drags a flow the user didn't mean, and wrecks a half-finished board. Guard it.
>
> ⚠️ If the game ever sets `FLAG_SECURE`, MediaProjection returns a **black frame** and there is no
> workaround. Not true of Flow Free today.

**Done when:** one tap completes a real level end-to-end, flow by flow.

## Cross-cutting / later

- Robustness: themes, DPI, notches, ad banners in detection.
- Settings: overlay vs auto-draw, capture hotkey, per-device calibration.
- Nice-to-have: step-by-step "explain the solution" using the human heuristics (doc 02).

## Guiding principle

Every phase stands alone and is verifiable. The solver is proven on desktop before a line of
Android is written; CV is proven on saved screenshots before it runs live. No phase depends on a
later one to be testable.
