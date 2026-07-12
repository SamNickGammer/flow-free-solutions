# 01 — Problem Analysis

## The game

Flow Free is played on an `R × C` grid. The board has `K` pairs of colored **endpoints**
(dots). A solution assigns every cell to exactly one color such that:

1. **Connectivity** — each color's two endpoints are joined by one simple path (a "flow").
2. **No crossing** — no cell belongs to two different flows.
3. **Full coverage** — every cell on the board is part of some flow (no blanks).

Endpoints have degree 1 in the solution (a pipe leaves the dot in exactly one direction).
Every non-endpoint cell has degree 2 (a pipe enters and leaves).

Coverage is the rule that separates Flow Free from generic "connect the dots": you cannot just
find *any* path between each pair — the paths must *tile the whole board*. This constraint is
what usually forces a **unique** solution and what makes casual guessing fail.

## Worked micro-example

A 3×3 with two colors. `A` and `B` are endpoints, `.` is empty:

```
input           one valid solution
A . B           A A B
. . .     →     A B B
A . B           A B B
```

Color A: top-left → down the left column → across the bottom. Color B fills the rest. Every
cell used, no crossings, both pairs joined.

## Grid coordinates & notation

- Cells addressed `(row, col)`, `0`-indexed from the top-left.
- Neighbors are the 4 orthogonal cells (no diagonals).
- **Text format** we'll use for the CLI and test fixtures: one row per line, one char per cell.
  Uppercase letters = endpoints, `.` = empty. Two cells sharing a letter are that color's pair.

```
Example 5×5 (Flow Free "Regular Pack" style):
B . . . R
. . . . .
. . Y . .
. G . . .
B R Y G .
```

## The Bridges variant

**Flow Free: Bridges** adds **crossover cells**. On a bridge cell, two flows pass through the
same cell without connecting — one goes horizontally, one goes vertically. They cross *over/under*
each other.

Model implications:

- A normal cell holds **one** color and has degree 2 (or 1 at an endpoint).
- A **bridge cell** holds **two** flows simultaneously: exactly one horizontal pass-through and
  one vertical pass-through. The horizontal pair (left↔right) is one color's segment; the
  vertical pair (up↔down) is another color's segment. They must be *different* colors and must
  each go **straight through** (no turning on a bridge).
- Coverage still applies; bridge cells count as covered by both flows.

Data-model consequence: a cell is no longer "one color". It's better modeled as **edges between
adjacent cells** carrying a color, with the constraint that a bridge cell allows the H edge-pair
and V edge-pair to carry different colors, while a normal cell forces all its used edges to share
one color. See [04-solver-design.md](04-solver-design.md).

Reference boards (5×5, levels 1–30) are catalogued at givemetheanswer (see
[references.md](references.md)) — useful as a test corpus for the bridges model.

## Why it's hard (complexity)

Flow Free is a form of the **Numberlink** puzzle. Deciding whether a Numberlink instance has a
solution is **NP-complete** — including the "cover every cell" variant. So there is no known
polynomial algorithm; solvers rely on **search + strong pruning** or a **reduction to SAT**.

In practice, though, real Flow Free levels are *designed to be human-solvable* and have unique
solutions. That structure (forced corners, tight coverage) makes them very tractable for a
pruned search — most boards collapse in milliseconds once dead-end detection kicks in.

Practical size range we target: **5×5 up to ~15×15** (the app's largest packs). Search with
connectivity + coverage pruning handles this range comfortably on a phone.

## What the solver must output

For each color: the ordered list of cells from one endpoint to the other. That path list is
what the renderer overlays and what the auto-draw traces as a swipe gesture.
