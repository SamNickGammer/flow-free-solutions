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
- **Text format** we'll use for the CLI and test fixtures: dimensions, then one row per line, one
  char per cell. Uppercase letters = endpoints, `.` = empty. Two cells sharing a letter are that
  color's pair. Optional directives follow the grid.

```
5 5
B . . . R
. . . . .
. . Y . .
. G . . .
B R Y G .
WALL 1,1 1,2      # blocks the edge between (1,1) and (1,2) — both cells still exist
HOLE 2,3          # cell (2,3) does not exist — excluded from coverage
BRIDGE 3,2        # crossover cell (two flows pass straight through)
```

**One format, every variant.** No per-variant flags or parsers — see
[levels/README.md](levels/README.md).

## Level variants

Flow Free has ~30 pack categories (Mania, Bridges, Walls, Obstacles, Cubes, Rectangle, Links,
Warps, Hexes). They are all the **same puzzle on a different graph**, and each reduces to one of
three parse-time operations:

- **remove edges** → walls (a bold line between two cells that a flow can't cross; **both cells
  still exist and still must be filled**)
- **remove nodes** → obstacles/holes (the cell isn't part of the board and **must not be filled**)
- **add edges** → cube seams, warps, portals

Plus **bridges**, which mark a node as carrying two straight passes (one H, one V, different
colors). This is the one variant that touches the solver.

⚠️ **Walls and obstacles are opposites, not variations.** A wall cell counts toward coverage; a
hole does not. Confusing them makes every board report unsolvable.

**Full breakdown, one doc per level type: [levels/](levels/).** That directory — not this file — is
the reference for variant mechanics.

## Why it's hard (complexity)

Flow Free is a form of the **Numberlink** puzzle. Deciding whether a Numberlink instance has a
solution is **NP-complete** — including the "cover every cell" variant. So there is no known
polynomial algorithm; solvers rely on **search + strong pruning** or a **reduction to SAT**.

In practice, though, real Flow Free levels are *designed to be human-solvable* and have unique
solutions. That structure (forced corners, tight coverage) makes them very tractable for a
pruned search — most boards collapse in milliseconds once dead-end detection kicks in.

Practical size range we target: **5×5 up to 15×15** (15×15 Mania is the app's largest, plus Mega
Mania at 11×14 / 12×15). Search with connectivity + coverage pruning handles this range comfortably
on a phone — but 14×14 is where a naive solver dies, so it's the benchmark that proves the pruning
works. See [levels/01-mania.md](levels/01-mania.md).

Note that **walls and obstacles make boards *easier* to search**, not harder: they remove edges and
nodes, which shrinks the branching factor and makes the deadend/forced-move prunes fire earlier. A
heavily-constrained board is a gift.

## What the solver must output

For each color: the ordered list of cells from one endpoint to the other. That path list is
what the renderer overlays and what the auto-draw traces as a swipe gesture.
