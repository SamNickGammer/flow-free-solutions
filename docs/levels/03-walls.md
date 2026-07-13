# Walls

**Packs:** Courtyard, Courtyard Spin, Pathway, Star Field, Pockets, Cataclysm. Walls also appear
inside Bridges and Hexes levels.

## What it is

A **bold line drawn on the border between two adjacent cells**. A flow **cannot cross it**.

Both cells still exist. Both cells still have to be filled. You just can't step directly from one
to the other — you have to go around.

## See it — puzzle and solution

> Legend: `.` empty · uppercase = **endpoint** · lowercase = that colour's **pipe** ·
> `┃` `━━━` = **wall** · `#` = hole · `╬` = bridge.
> All diagrams below are **generated and machine-verified** — see [Reproducing](#reproducing).

```
PUZZLE                  SOLUTION
──────                  ────────
 .   .   .   .   B       b───b───b───b───B
                         │
 B   .   .   .   C       B   a───a───a   C
                             │       │   │
 A   . ┃ A   .   .       A───a ┃ A───a   c
                                         │
 .   D   .   .   .       d───D   c───c   c
                         │       │   │   │
 D   C   .   .   .       D   C───c   c───c
```

Look at flow **A**. Its endpoints are `(2,0)` and `(2,2)` — two cells apart on the same row. The
wall sits between `(2,1)` and `(2,2)`, so **A cannot just walk straight across**. It is forced to
detour: `(2,0) → (2,1) → up → across the row above → down → (2,2)`.

**That is what a wall does to a solution.** All 25 cells still get filled, including both cells
touching the wall.

## The wall is load-bearing — same endpoints, wall on/off

Same puzzle, solved twice. The only difference is the one wall:

```
SOLUTION (no wall)      SOLUTION (wall added)
──────────────────      ─────────────────────
 b───b   b───b───B       b───b───b───b───B
 │   │   │               │
 B   b───b   c───C       B   a───a───a   C
             │               │       │   │
 A───a───A   c───c       A───a ┃ A───a   c
                 │                       │
 d───D   c───c   c       d───D   c───c   c
 │       │   │   │       │       │   │   │
 D   C───c   c───c       D   C───c   c───c
```

On the left, **A** runs straight across: `A───a───A`. Add the wall and that route dies — the whole
board reorganises. Every colour's path changes.

This is why a wall fixture must be **load-bearing**: a solver that ignores walls entirely would
still pass a test whose wall happened to sit off the solution path.

## Graph transform

**Remove the edge.** Nothing else.

```
WALL 1,1 1,2      →   Edges.remove( (1,1) ↔ (1,2) )
```

- Both nodes **remain** in `Nodes`.
- Both nodes **still count toward coverage**.
- The edge is removed in **both directions**.

That's the entire implementation. One line at parse time.

## The critical distinction — walls are NOT obstacles

This is the mistake to avoid, and it's why walls get their own doc:

> **A wall removes an EDGE. An obstacle removes a NODE.**

| | Wall | Obstacle |
|---|---|---|
| Cells on both sides exist | ✅ yes | ❌ the cell is gone |
| Must be filled (coverage) | ✅ **yes** | ❌ **no** |
| Graph op | remove edge | remove node + its edges |

If you model a wall as a removed cell, the solver stops trying to fill a cell that **must** be
filled → it returns a "solution" with a hole in it, or finds nothing.

If you model an obstacle as a wall, the solver keeps trying to fill a cell that **can't** be
filled → **every board reports unsolvable**, and you'll spend a day blaming the search.

### Demonstrated

Here is that exact bug, run through the solver. Take a solvable board with a **hole** at `(2,2)`:

```
PUZZLE: HOLE at (2,2)      SOLUTION — 24 cells filled
─────────────────────      ──────────────────────────
 B   A   .   .   .          B   A───a   a───a
                            │       │   │   │
 .   .   .   .   .          b───b   a───a   a
                                │           │
 .   .   #   A   .          b───b   #   A───a
                            │
 .   B   C   D   .          b   B   C   D───d
                            │   │   │       │
 .   .   C   D   .          b───b   C   D───d
```

Now model that same obstacle **as walls** instead — fence `(2,2)` off on all four sides. Same
endpoints, cell still present:

```
 B   A   .   .   .
                            solver result:  UNSOLVABLE
 .   .   .   .   .
        ━━━                 (2,2) has no usable edges left,
 .   . ┃ . ┃ A   .          but coverage still demands it be filled.
        ━━━
 .   B   C   D   .

 .   .   C   D   .
```

**Walling a cell off is not the same as removing it.** The walled cell still has to be filled, and
now nothing can reach it — so the board is dead. Get this wrong in the detector and *every* level
reports unsolvable.

### They are not even interchangeable in principle

There's a **parity invariant** that makes this precise. Colour the cells like a chessboard by
`(r+c) % 2`; the grid graph is bipartite, so a flow alternates black/white with every step.

- A flow with **both endpoints on the same colour** has an odd cell-count, eating one more of that
  colour than the other (**±1**).
- A flow with **endpoints on opposite colours** eats an equal number of each (**0**).

Full coverage means the flows partition every node, so summing over all colours:

```
(#even cells) − (#odd cells)  =  S − T
     where S = colours with BOTH endpoints on even cells
           T = colours with BOTH endpoints on odd cells
```

A 5×5 board has 13 even and 12 odd cells → any solution needs `S − T = 1`. Punch out one even cell
(a hole) → 12 and 12 → now it needs `S − T = 0`.

**`S − T` depends only on where the endpoints are.** So the same endpoint set can *never* solve both
the walled board and the holed board — removing a node always flips the parity budget by one. Wall
and obstacle aren't two settings of one knob; **they are different boards.**

Measured on the verified solutions in these docs:

| Board | nodes | `#even − #odd` | `S − T` | |
|---|---|---|---|---|
| plain 5×5 | 25 | `+1` | `+1` | holds |
| **5×5 + wall** | **25** | **`+1`** | **`+1`** | holds — *the walled cell is still a node* |
| **5×5 + hole** | **24** | **`0`** | **`0`** | holds — *the node is gone, budget flips* |
| 6×6 courtyard | 32 | `0` | `0` | holds |

The wall board and the hole board demand **different** endpoint parities. That's the distinction,
in arithmetic.

*(Holds for plain/walled/holed boards. [Bridges](05-bridges.md) break the count — a bridge node is
visited twice, so the flows no longer partition the nodes.)*

This is also a **free solvability pre-check**: compute `S − T` from the endpoints at parse time and
compare to `#even − #odd`. If they disagree, the board is unsolvable and no search is needed. It
costs O(cells) and catches detector misreads instantly — which, given how easily a wall gets read as
a hole, is exactly the bug you want caught before a 14×14 search runs.

Get this boundary right at parse time and the solver is correct for free. See
[04-obstacles.md](04-obstacles.md).

## Solver notes

**Zero changes.** Every prune already handles walls, because they all walk `Edges`:

- **Reachability** — flood fill along `Edges`; a walled-off route simply isn't traversable.
- **Deadend check** — "a node with <2 usable edges can't be a degree-2 pass-through" now fires on
  walled cells automatically. A cell boxed in by three walls is instantly dead.
- **Coverage** — unchanged; walled cells are still nodes, still counted.

Walls actually make search **faster**: they cut the branching factor and make the deadend and
forced-move prunes fire much earlier. A heavily-walled board is more constrained, not harder.

## Detection notes — the hard part

Walls are the trickiest thing to read off a screenshot, because you're looking for a line **on a
cell border**, not a property of the cell.

Approach:
1. You already know the grid geometry (origin + cell size) from grid detection.
2. For each pair of adjacent cells, sample a thin strip **along the shared border**.
3. A wall = a run of high-contrast (typically bright/white/thick) pixels along that strip.
   No wall = background-colored border or a thin faint gridline.

The signal is **wall line vs normal gridline** — both are lines on the border. Discriminate on
**thickness and brightness**, not mere presence. Calibrate the threshold; it will vary by theme.

*(ponytail: threshold on border-strip brightness first. Only reach for edge-detection/Hough lines
if the simple strip sample misclassifies.)*

Expect this to need a **manual confirm/nudge** on first run — it's the most likely misread in the
whole pipeline, and a single missed wall makes the board unsolvable with no obvious clue why.

## Test fixtures

- A board that is **solvable only because** of a wall (the wall forces the correct route) — proves
  walls are actually being applied, not silently ignored.
- A board that becomes **unsolvable** if a wall is dropped — a regression test that catches a
  detector silently missing walls.
- A cell **boxed in by 3 walls** — proves the deadend prune fires.

The first two matter most: a solver that *ignores* walls entirely will still pass a naive fixture
if the wall happened not to be on the solution path. Pick fixtures where the wall is load-bearing.
