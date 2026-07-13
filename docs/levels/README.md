# Level Types — One Graph Model

Flow Free ships ~30 pack categories. They look like different games. **They aren't.** Every one
of them is the same puzzle on a different **graph**.

This is the single most important design decision in the repo: model the board as a graph, and
**every variant becomes a node/edge transform applied before the solver runs.** The solver
itself never changes.

## The universal model

```
Board = (Nodes, Edges, Bridges)

  Nodes    the playable cells.        Coverage rule: every NODE must be filled.
  Edges    legal moves between nodes. A flow may only step along an Edge.
  Bridges  nodes that allow two straight passes (one H, one V) at once.
```

That's it. The solver's flood fills, reachability checks, and coverage counter all walk `Nodes`
and `Edges` — they don't know or care what "shape" the board is.

## Every variant as a transform

| Variant | Packs (examples) | Transform on the graph |
|---|---|---|
| **[Mania](01-mania.md)** | 5×5 … 15×15 Mania, Mega (11×14, 12×15) | **None.** Just bigger. Pure scale test. |
| **[Rectangle](02-rectangle.md)** | Rectangle, Tower, Hourglass, Shifted | **None.** `R ≠ C`. Already supported. |
| **[Walls](03-walls.md)** | Courtyard, Pathway, Star Field, Pockets, Cataclysm | **Remove edges.** Cells stay, the move between them is blocked. |
| **[Obstacles](04-obstacles.md)** | Worm, Amoeba, Inkblot, Scattered | **Remove nodes.** The cell doesn't exist and must *not* be covered. |
| **[Bridges](05-bridges.md)** | Bridges packs, Bridges Sampler | **Mark nodes** as crossover (two straight passes). |
| **[Links](06-links.md)** | Hoop, Loop, Chain, Chain Maze | Node removal shaping the board into a ring/chain topology. |
| **[Cubes](07-cubes.md)** | Cube Pack, Triple Cube, Cube Stacks | **Add edges** across cube-face folds. |
| *Warps* | Warps packs | **Add edges** wrapping opposite borders. |
| *Hexes* | Hexes packs | 6 neighbors instead of 4 — a different edge set. |

Read the table again: **remove edges, remove nodes, add edges.** Three operations cover the
entire game.

## Reading the diagrams

Every level doc shows a **puzzle and its solution**, side by side. Same notation throughout:

| Symbol | Meaning |
|---|---|
| `.` | empty cell |
| `A` `B` `C` | **endpoint** (uppercase) — the two dots you must connect |
| `a` `b` `c` | **pipe** (lowercase) — that colour's path through a cell |
| `───` `│` | the pipe's route between cells |
| `┃` `━━━` | **wall** — blocks movement between two cells ([03](03-walls.md)) |
| `#` | **hole** — not a cell; must NOT be filled ([04](04-obstacles.md)) |
| `╬` | **bridge** — two flows cross here ([05](05-bridges.md)) |

Baseline — a plain 5×5, no variants:

```
PUZZLE                  SOLUTION
──────                  ────────
 D   .   .   .   .       D───d───d   c───c
                                 │   │   │
 .   .   D   C   .       a───a   D   C   c
                         │   │           │
 A   .   .   .   .       A   a   c───c   c
                             │   │   │   │
 A   .   .   .   .       A───a   c   c   c
                                 │   │   │
 B   B   C   .   .       B───B   C   c───c
```

Every cell ends up filled — that's the **coverage** rule, and it's why `C` takes such a long way
round rather than connecting directly.

> **These diagrams are generated, not drawn.** Each one is produced by a working solver and passed
> through an independent verifier that checks: every flow joins its endpoints, every step is a
> legal edge, no cell is double-used, and every node is covered. See
> [Reproducing them](#reproducing-them).

## Why this matters — the wall vs obstacle distinction

This is the trap, and it's the thing you asked about. They *look* similar on screen. They are
completely different to the solver:

| | **Wall** (bold line between cells) | **Obstacle** (cell is gone / blacked out) |
|---|---|---|
| Cells exist? | **Yes, both cells exist** | **No, the cell is removed** |
| Must be covered? | **Yes** — both cells still need filling | **No** — it is not part of coverage |
| Graph op | Delete the **edge** between them | Delete the **node** and all its edges |
| Get it wrong → | Solver can't find a path | Solver reports "unsolvable" forever (chasing a cell that can't be filled) |

Conflating these is the #1 way a Flow solver silently breaks. A wall cell **still counts toward
100% coverage**; an obstacle cell **does not**. If you model an obstacle as a wall, the solver
demands you fill a cell that doesn't exist and every board becomes unsolvable.

## Text format (v2)

One parser, all variants. Cell grid plus directives:

```
5 5
B . . . R
. . . . .
. . Y . .
. G . . .
B R Y G .
WALL 1,1 1,2      # blocks the edge between (1,1) and (1,2) — both cells still exist
HOLE 2,3          # cell (2,3) does not exist — not part of coverage
BRIDGE 3,2        # crossover cell
```

- `WALL r1,c1 r2,c2` — the two cells must be adjacent. Removes that one edge (both directions).
- `HOLE r,c` — removes the node. Excluded from coverage.
- `BRIDGE r,c` — marks a crossover node.

Line-based and dumb on purpose. *(ponytail: no ASCII-art wall parser — `WALL` lines are trivial
to write, trivial to parse, and impossible to misread.)*

## Solver impact: none

Worth stating plainly, because it's the payoff:

- **Reachability check** — flood fill along `Edges`. Walls just aren't edges. Holes just aren't
  nodes. No special-casing.
- **Coverage check** — `emptyCount` counts only `Nodes`. Holes were never counted.
- **Deadend check** — "a node with <2 usable edges can't be a pass-through" already handles walls
  and holes for free.

Add the transforms at **parse time**. The search loop in
[../04-solver-design.md](../04-solver-design.md) is untouched.

## Detection impact: this is where the real work is

The solver is easy. **Telling a wall from an obstacle from a screenshot is the hard part.**

- **Wall** — a thick bold line *on a cell border*, cells on both sides look normal.
- **Obstacle** — the *cell itself* is dark/absent/textured.
- **Bridge** — a distinct crossover glyph *in* the cell.

Detection must classify all three. See [../03-architecture.md](../03-architecture.md). Expect to
need a manual confirm step here first — this is the part most likely to misread.

## Reproducing them

The diagrams in these docs come from a working prototype solver in [`../../tools/`](../../tools/):

```
python3 tools/gen_diagrams.py        # regenerates every diagram in docs/levels/
```

`tools/flow.py` implements the graph model described above — `nodes` / `edges` / `bridges`, DFS
with most-constrained-color selection, and the reachability/coverage/deadend prunes. `verify()` is
an **independent checker** that does not trust the solver: it re-walks each path and asserts every
rule from scratch.

It is a **prototype**, not the shipping solver — Python, single-threaded, and it exists to prove
the design and generate verified diagrams. The real `:solver` module (Phase 1) is a port of it.

That it works is itself the evidence for the claims on this page: **one solver, seven variants, and
the only variant that touched the search loop was bridges.**

## Confidence note

The **mechanics** above (wall = edge, obstacle = node, bridge = dual-pass) are solid and are what
the code should be built on. The **pack-name → mechanic mapping** in the table is inferred from
pack listings and may be off for specific packs (especially Links/Cubes — see those docs). Confirm
against real screenshots before hardcoding any pack assumptions. The graph model is correct
regardless of how the packs are named.
