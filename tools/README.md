# tools/ — prototype solver & diagram generator

A working Flow Free solver in Python. It exists for two reasons:

1. **To prove the design** in [`../docs/04-solver-design.md`](../docs/04-solver-design.md) before
   committing to it — one graph model, every variant a parse-time transform.
2. **To generate the diagrams** in [`../docs/levels/`](../docs/levels/) so they are *verified*
   rather than hand-drawn. A hand-drawn Flow Free "solution" that quietly violates coverage is
   worse than no diagram at all.

```
python3 gen_diagrams.py     # regenerate every puzzle/solution diagram in docs/levels/
```

## Files

| File | What |
|---|---|
| `flow.py` | The graph model, the DFS solver, an independent `verify()`, and a puzzle generator. |
| `render.py` | ASCII renderer — puzzle panel + solution panel, side by side. |
| `gen_diagrams.py` | Builds one worked example per level type and prints it. |

## What it validates

`flow.py` implements exactly the design the docs describe:

- **`nodes` / `edges` / `bridges`** — neighbours come from `adj`, never `r±1` arithmetic; coverage
  counts `nodes`, never `rows × cols`.
- **DFS** with most-constrained-color selection.
- **Prunes**: per-color reachability, coverage reachability, and the deadend rule. All three walk
  the edge set, which is why they handle walls/holes/seams with no special cases.

The payoff, tested: **seven variants — Mania, Rectangle, Walls, Obstacles, Bridges, Links (holes
*and* portals), Cubes (folded seams) — and the only one that touched the search loop was bridges.**

## verify() does not trust the solver

Independent checker. It re-walks each returned path from scratch and asserts:

- every flow joins its two endpoints
- every step is a **real edge** (so a wall or hole violation is caught)
- no cell is used by two flows (except a valid bridge H/V pair)
- **every node is covered** — and no hole is filled
- no flow turns on a bridge; each bridge is crossed on both axes

Every diagram in the docs passed this.

## Not the shipping solver

Python, single-threaded, and the puzzle *generator* (Hamiltonian-path-cut) is slow and only used
here to invent example boards. The real `:solver` module (Phase 1, see
[`../docs/05-roadmap.md`](../docs/05-roadmap.md)) is a port of `flow.py`'s model and search to
plain JVM/Kotlin so it runs on desktop and inside the Android app unchanged.

*(ponytail: prototype in the throwaway language, port the proven design. The generator is scaffolding —
it does not get ported.)*
