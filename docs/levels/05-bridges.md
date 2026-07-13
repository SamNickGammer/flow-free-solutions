# Bridges

**Packs:** Flow Free: Bridges (all packs), Bridges Sampler, Variety Pack bridges levels.

## What it is

A **crossover cell**. Two flows pass through the same cell without connecting — one goes
horizontally, one goes vertically, crossing over/under each other.

```
        │ R
        │
  B ────┼──── B      the bridge cell carries BOTH flows
        │              horizontal: blue passes L→R
        │ R            vertical:   red passes U→D
                       they cross, they do not connect
```

Bridges levels **also contain walls** — the two mechanics appear together. See
[03-walls.md](03-walls.md).

## The rules of a bridge cell

1. It carries **exactly two** passes: one horizontal (left↔right), one vertical (up↔down).
2. The two passes must be **different colors**.
3. Each pass must go **straight through**. **No turning on a bridge.** A flow that enters from the
   left must exit right. Enter from the top, exit the bottom.
4. It counts as covered when **both** passes are filled.

Rule 3 is the one that constrains hardest — and it's a gift to the solver, because it makes a
bridge cell's behavior almost fully forced once a flow enters it.

## Why this breaks the naive model

Everything up to now assumed **one color per cell** (`owner[r][c]`). A bridge cell holds **two**.
That single-color assumption is now dead.

**This is why the model is edge-based, not cell-based.** Color lives on the **edge between two
cells**, not on the cell:

```
cell-based (broken for bridges)     edge-based (correct)

owner[r][c] = BLUE                  edge (r,c)↔(r,c+1) = BLUE     ← horizontal pass
   ...only one color fits           edge (r,c)↔(r+1,c) = RED      ← vertical pass
                                       both live on the same cell, no conflict
```

## Graph transform

**Mark the node** as a crossover, and change what "consistent" means at that node:

| Node type | Constraint on its used edges |
|---|---|
| **Normal cell** | All used edges share **one** color; exactly **2** used (degree 2). |
| **Endpoint** | Exactly **1** used edge. |
| **Bridge cell** | H edge-pair (L+R) is one color, V edge-pair (U+D) is another. Both pairs used, straight through, colors differ. |

```
BRIDGE 3,2
```

## Solver notes

The search loop is unchanged. What changes is the **node consistency rule** above and how
traversal works at a bridge:

- **Traversal** — when a flow enters a bridge, its **next move is forced**: straight out the far
  side. No branching. Bridges *reduce* the search space.
- **Reachability flood fill** — must walk the **edge graph**, so a bridge node can be crossed
  twice, once per axis. A cell-based flood fill will wrongly treat a bridge as "occupied" and
  block the second flow. **This is the bug to watch for.**
- **Coverage** — a bridge node needs **both** passes filled to count as covered. It contributes
  *two* units of fill, not one.

> If you build the cell-based solver first (Phase 1) and bolt bridges on later, expect to rewrite
> the flood fills. Building the graph/edge model from the start is why
> [../04-solver-design.md](../04-solver-design.md) leads with it.

## Detection notes

The bridge glyph is a **distinct visual marker in the cell** (a small overpass/crossing icon,
often silver). It's an in-cell classification, like an obstacle — easier than wall detection.

Three-way cell classification: **empty**, **endpoint** (saturated color), **bridge** (glyph).
Plus separate border-strip scanning for walls.

## Test fixtures

The **5×5 Bridges levels 1–30** corpus (see [../references.md](../references.md)) is the natural
starting set — small enough to verify by hand, real enough to catch model bugs.

Assertions the self-check must make:

- Both passes on a bridge cell are filled, and are **different colors**.
- Each pass goes **straight through** (no turn recorded at a bridge).
- Bridge cells count correctly toward coverage (both passes).
- **Negative test:** a cell-based flood fill would block the second flow — assert a board that
  *requires* two flows through one bridge actually solves. That test is what proves the edge model
  is really in use and not quietly cell-based underneath.
