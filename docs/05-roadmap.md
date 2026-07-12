# 05 — Roadmap

Phased so each phase ships something runnable and testable on its own. Solver-first, mobile last —
the risky CV/Android work rides on top of a proven engine.

## Phase 1 — Solver core (desktop CLI)

**Build:** the `:solver` module (model + DFS search + pruning) and a CLI that reads a text grid
and prints the solution.

- Data model, DFS with most-constrained-color, `prune_ok` (reachability + coverage + deadend).
- Text grid I/O (format in [01-problem-analysis.md](01-problem-analysis.md)).
- Fixtures + self-check (3×3, several 5×5).

**Done when:** `flowsolve puzzle.txt` solves all fixtures with valid, full-coverage output, and
the self-check passes. No Android, no CV yet.

## Phase 2 — Bridges support

**Build:** edge-graph model + crossover handling in the solver.

- Bridge cells: H/V pass-through, straight-only, dual coverage.
- Import the 5×5 bridges corpus (levels 1–30) as fixtures.

**Done when:** the bridges fixtures solve correctly and the self-check validates dual-color
bridge cells.

## Phase 3 — Grid detection (screenshot → model)

**Build:** `:detection` — bitmap in, puzzle model out.

- Locate board bounds + cell size; sample cell centers; cluster into `K` endpoint pairs.
- First version: **manual grid confirm/nudge** (calibration knob), then automate.
- Bridge-glyph detection.

**Done when:** feeding a saved Flow Free screenshot produces a model that the Phase 1 solver
solves. Test against a folder of screenshots.

## Phase 4 — Mobile shell (Android)

**Build:** `:app`, `:capture`, `:overlay`.

- Foreground service + persistent notification (stay alive in background).
- Floating overlay button (`SYSTEM_ALERT_WINDOW`).
- MediaProjection one-frame capture on tap.
- Wire capture → detection → solver → **overlay render** of the solution on the grid.

**Done when:** open a level, tap the button, and see correct solution lines drawn over the game.
You trace them by hand.

## Phase 5 — Auto-draw

**Build:** `:autodraw` — AccessibilityService gesture injection.

- Map solved cell paths → screen-pixel swipe gestures (one swipe per color).
- One-tap "solve it for me" that draws every flow.
- Calibration offset so gestures land on-cell.

**Done when:** one tap completes a real level end-to-end.

## Cross-cutting / later

- Robustness: themes, DPI, notches, ad banners in detection.
- Settings: overlay vs auto-draw, capture hotkey, per-device calibration.
- Nice-to-have: step-by-step "explain the solution" using the human heuristics (doc 02).

## Guiding principle

Every phase stands alone and is verifiable. The solver is proven on desktop before a line of
Android is written; CV is proven on saved screenshots before it runs live. No phase depends on a
later one to be testable.
