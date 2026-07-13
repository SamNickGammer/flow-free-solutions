# Walls

**Packs:** Courtyard, Courtyard Spin, Pathway, Star Field, Pockets, Cataclysm. Walls also appear
inside Bridges and Hexes levels.

## What it is

A **bold line drawn on the border between two adjacent cells**. A flow **cannot cross it**.

Both cells still exist. Both cells still have to be filled. You just can't step directly from one
to the other — you have to go around.

```
   before wall              with wall between (1,1)-(1,2)

   . . . . .                . . . . .
   . A→B . .                . A ┃ B . ←  A cannot step right into B
   . . . . .                . . ┃ . .     it must route around
   . . . . .                . . . . .
```

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
