# Mania

**Packs:** 5×5 Mania (Kids) through 15×15 Mania, plus Mega Mania (11×14, 12×15).

## What it is

Plain Flow Free rules. The *only* difference is **size**. No walls, no holes, no bridges. Standard
grid, 4-neighbor adjacency, full coverage.

Most Mania packs sit in the **9×9 – 14×14** range, with 15×15 at the top end.

## Graph transform

**None.** `Nodes` = all `R×C` cells, `Edges` = all orthogonal adjacencies.

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
