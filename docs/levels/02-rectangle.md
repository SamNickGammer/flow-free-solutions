# Rectangle

**Packs:** Rectangle, Tower, Hourglass, Shifted, Extreme Rectangle, Jumbo Rectangle.

## What it is

The board is **not square**. `R ≠ C` — e.g. 9×11, 10×14, tall "Tower" boards, wide boards.

Rules are otherwise plain Flow Free: 4-neighbor adjacency, full coverage.

## Graph transform

**None.** The model was never square-only. `Nodes` = all `R×C` cells with `R ≠ C`.

If any code path assumes `R == C`, that's a bug, not a feature. Keep `rows` and `cols` as separate
values everywhere — never a single `size`.

## See it — puzzle and solution

> Generated and machine-verified. 4 rows × 7 cols.

```
PUZZLE                          SOLUTION
──────                          ────────
 .   A   D   .   .   D   E       a───A   D───d   d───D   E
                                 │           │   │       │
 A   B   C   .   .   E   .       A   B   C   d   d   E   e
                                     │   │   │   │   │   │
 B   .   .   .   .   .   .       B───b   c   d   d   e   e
                                         │   │   │   │   │
 C   .   .   .   .   .   .       C───c───c   d───d   e───e
```

Nothing new — it just isn't square. The value of this fixture is purely defensive: it fails loudly
the moment someone reintroduces a single `size` field or writes `for i in range(n): for j in
range(n)`.

## The catch: Tower / Hourglass / Shifted

These pack names suggest the board is **not a full rectangle** — an hourglass shape pinches in the
middle, "Shifted" suggests offset rows. If so, they are **not** pure Rectangle levels: they are
Rectangle **+ node removal**, i.e. an [Obstacles](04-obstacles.md) board.

Handle them by composing the transforms — a shaped board is just `HOLE` directives on a rectangle:

```
10 6
... grid ...
HOLE 0,0
HOLE 0,5
HOLE 9,0
HOLE 9,5
```

That's the whole point of the graph model: **variants compose.** You don't need a "Tower solver".

> ⚠️ Unconfirmed which of Tower/Hourglass/Shifted are true rectangles vs shaped boards. Check a
> real screenshot before assuming. Either way the model already covers both.

## Solver notes

Nothing special. Worth noting: non-square boards have **different parity/coverage behavior** than
square ones, so a solver bug that only shows on odd dimensions will hide if you only ever test
`N×N`. Include at least one `R ≠ C` fixture.

## Detection notes

**Don't infer `C` from `R`.** Detect grid lines in both axes independently. A detector that
finds 9 rows and assumes a 9×9 board will silently mis-parse every Rectangle level — and it'll
look like a solver bug, not a detection bug.

Cells stay square on screen; only the *count* differs per axis.

## Test fixtures

At minimum one tall (`R > C`) and one wide (`R < C`) fixture. Cheap insurance against
the `size` assumption creeping back in.
