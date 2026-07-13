# Mania

**Packs:** 5×5 Mania (Kids) through 15×15 Mania, plus Mega Mania (11×14, 12×15).

## What it is

Plain Flow Free rules. The *only* difference is **size**. No walls, no holes, no bridges. Standard
grid, 4-neighbor adjacency, full coverage.

Most Mania packs sit in the **9×9 – 14×14** range, with 15×15 at the top end.

## Graph transform

**None.** `Nodes` = all `R×C` cells, `Edges` = all orthogonal adjacencies.

## See it — puzzle and solution

> Generated and machine-verified. 9×9, 8 colours.

```
PUZZLE                                  SOLUTION
──────                                  ────────
 G   H   .   .   H   .   .   .   .       G   H   h───h   H   c───c───c───c
                                         │   │   │   │   │   │           │
 .   .   .   .   .   .   .   .   .       g   h───h   h───h   c───c   c───c
                                         │                       │   │
 .   .   F   .   B   B   C   .   .       g───g   F───f   B───B   C   c───c
                                             │       │                   │
 .   .   G   .   A   .   .   .   .       g───g   G   f   A───a───a   c───c
                                         │       │   │           │   │
 .   .   .   .   .   .   A   .   C       g───g   g   f   f───f   A   c───C
                                             │   │   │   │   │
 .   .   .   .   .   .   .   .   D       g───g   g   f   f   f   e───e   D
                                         │       │   │   │   │   │   │   │
 .   .   .   .   .   .   .   .   D       g───g   g   f   f   f   e   e   D
                                             │   │   │   │   │   │   │
 .   .   .   .   .   F   .   .   E       g───g   g   f   f   F   e   e   E
                                         │       │   │   │       │   │   │
 .   .   .   .   .   E   .   .   .       g───g───g   f───f   E───e   e───e
```

Note how sparse the puzzle is — 81 cells, only 16 endpoints. **Coverage** is what pins the
solution down: `G` and `F` snake through half the board precisely because every cell must be
filled. This is why the "just connect the pairs" instinct fails at scale.

**Search cost: 9,009 nodes explored.** Compare with a 5×5, which typically resolves in **~24
nodes**. That's the exponential blowup the pruning has to fight — and the reason 14×14 is the
benchmark that matters, not 5×5.

## Why it still matters: this is the scale test

Mania is where a naive solver dies. It's the benchmark that proves the pruning actually works.

- A 5×5 solves by brute force. A **14×14 with 12+ colors does not** — the search space is
  astronomically larger.
- Every prune in [../04-solver-design.md](../04-solver-design.md) (reachability, coverage,
  stranded-pocket, deadend, forced-move propagation) exists to make boards *this size* tractable.
- If 14×14 Mania solves in well under a second, the engine is right. If it hangs, a prune is
  missing or wrong.

## Solver notes

- **Most-constrained-color selection matters most here.** On a big board with many colors,
  picking the wrong color to extend explodes the branching factor.
- **Forced-move propagation** pays off hugely — big boards have long forced corridors,
  especially early. Chaining those without branching collapses a lot of the tree.
- Expect the pruning to do the heavy lifting; expect very little actual backtracking on
  well-designed levels (they have unique solutions and are built to be human-solvable).

## Detection notes

Bigger grid = **smaller cells in pixels**. Center-pixel color sampling gets less reliable as cell
size shrinks. Sample a small patch (e.g. a few px square) and take the median, not one pixel.

## Test fixtures

Use Mania as the **performance corpus**, not the correctness corpus:

- Correctness → small boards (5×5) where you can eyeball the answer.
- Performance → 12×12, 14×14, 15×15. Assert a **time bound**, not just a correct answer.

A solver that's correct but takes 30s on 14×14 is a failed solver for a one-tap mobile app.
