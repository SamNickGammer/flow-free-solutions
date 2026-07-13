# Obstacles (holes / shaped boards)

**Packs:** Worm, Amoeba, Inkblot, Scattered. Also Courtyard (hole in the middle) and any
non-rectangular board shape.

## What it is

A cell that **is not part of the board**. Blacked out, absent, or outside the playable blob. No
flow may enter it, and — critically — **it does not need to be filled**.

## See it — puzzle and solution

> Legend: `.` empty · `#` **hole** · uppercase = endpoint · lowercase = that colour's pipe.
> Generated and machine-verified.

**Courtyard** — a solid block of holes in the middle. 6×6 = 36 cells, minus 4 holes = **32 nodes
to cover**:

```
PUZZLE                      SOLUTION
──────                      ────────
 .   .   C   B   .   B       d───d   C   B   b───B
                             │   │   │   │   │
 D   D   C   .   .   A       D   D   C   b───b   A
                                                 │
 E   .   #   #   .   .       E───e   #   #   a───a
                                 │           │
 .   .   #   #   .   .       e───e   #   #   a───a
                             │                   │
 .   E   .   .   A   .       e   E   a───a───A   a
                             │   │   │           │
 .   .   .   .   .   .       e───e   a───a───a───a
```

The four `#` cells are **left empty in the solution and that is correct** — they are not part of
the board. Every one of the other 32 cells is filled.

**Scattered** — isolated interior holes:

```
PUZZLE                  SOLUTION
──────                  ────────
 .   A   #   .   .       a───A   #   b───b
                         │           │   │
 .   .   .   .   B       a───a   b───b   B
                             │   │
 #   .   .   C   C       #   a   b   C───C
                             │   │
 .   .   B   D   .       a───a   B   D───d
                         │               │
 .   .   A   #   D       a───a───A   #   D
```

Flows route *around* the holes. 25 − 3 = **22 nodes covered**.

## Graph transform

**Remove the node**, and every edge touching it.

```
HOLE 2,3    →   Nodes.remove( (2,3) )
                Edges.remove( every edge incident to (2,3) )
```

## The coverage rule is what changes

This is the part that bites. Full coverage means **"every node is filled"** — *not* "every cell in
the `R×C` rectangle is filled".

```
emptyCount  counts NODES only.        Holes were never in Nodes, so never counted.
goal        emptyCount == 0.
```

If holes leak into the coverage count, the solver waits forever for a cell that can never be
filled and reports **every board unsolvable**. This is the single most likely bug in the whole
solver, and its symptom ("nothing solves") points nowhere near its cause.

> **Wall vs obstacle, one more time:** a wall cell **must** be covered. A hole **must not** be.
> Same visual family, opposite coverage semantics. See [03-walls.md](03-walls.md).

## Solver notes

**Zero changes**, if — and only if — `Nodes` is the source of truth:

- **Coverage** — counts `Nodes`. ✅ free.
- **Reachability** — flood fills over `Edges`, which no longer touch holes. ✅ free.
- **Deadend check** — a node next to a hole simply has fewer edges; the existing `<2 usable edges`
  rule fires correctly. ✅ free.

The one place to be careful: **anything that iterates `for r in rows: for c in cols:`** is a
latent bug. Iterate `Nodes`, never the rectangle. Grep for double loops over dimensions — that's
where holes leak back in.

## Shaped boards compose

Worm, Amoeba, Inkblot, Courtyard, Hourglass, Tower — these are all **just `HOLE` directives**.
There is no "Amoeba solver". You don't write new code for a new board shape; you emit different
`HOLE` lines at parse time. Same for [Links](06-links.md) (ring/chain topologies) and the shaped
[Rectangle](02-rectangle.md) packs.

This is the graph model paying rent.

## Detection notes

Easier than walls — you're classifying the **cell**, not its border.

- A hole is visually distinct: dark, textured, or matching the page background rather than the
  board background.
- Sample the cell interior (median of a patch). Three classes: **background** (empty cell),
  **saturated color** (endpoint), **hole** (dark / off-board).
- Blob shapes (Amoeba, Inkblot) put holes around the **perimeter**; Scattered puts them in the
  **interior**. Same detection either way — don't special-case position.

Watch for: the app's board background vs the page background can be close in some themes. That's
the misread to guard against — and it produces the "everything is unsolvable" symptom above.

## Test fixtures

- **Courtyard-style** — a solid block of holes in the middle. Proves coverage excludes them.
- **Scattered** — isolated interior holes. Proves per-node handling, not just region handling.
- **Blob** — non-rectangular perimeter. Proves nothing iterates the bounding rectangle.
- **Negative test:** take a solvable holed board, deliberately count holes as coverable, assert
  the solver reports unsolvable. That test *documents the bug* so it can't come back silently.
