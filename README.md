# Flow Free Solutions

An automated solver for **Flow Free** (and the **Flow Free: Bridges** variant), built as the
engine behind a bigger goal: a **mobile assistant that watches the screen, reads the puzzle,
solves it, and shows you the answer** — either as an overlay you trace by hand, or a one-tap
auto-draw.

> Status: **planning / docs first.** This repo currently holds the analysis and design. Code
> lands in phases (see the [Roadmap](docs/05-roadmap.md)).

---

## The big picture

The end product is not just a solver. It is a **screen-aware assistant**:

```
┌──────────────┐   capture    ┌──────────────┐   detect    ┌──────────────┐
│  Flow Free   │ ───────────► │ Screen grab  │ ──────────► │ Grid + color │
│  on screen   │  (trigger)   │  (bitmap)    │             │  extraction  │
└──────────────┘              └──────────────┘             └──────┬───────┘
                                                                  │ puzzle model
       overlay / auto-draw                                        ▼
┌──────────────┐   render     ┌──────────────┐   solve     ┌──────────────┐
│  You see /   │ ◄─────────── │  Solution    │ ◄────────── │    Solver    │
│  tap solve   │              │  paths       │             │  (engine)    │
└──────────────┘              └──────────────┘             └──────────────┘
```

A background service keeps a floating button on screen. You open a Flow Free level, tap the
button, and the app captures the screen, finds the grid, solves it, and draws the answer over
the game — or auto-traces it for you.

Full system design: **[docs/03-architecture.md](docs/03-architecture.md)**.

---

## What is Flow Free?

A grid puzzle. Pairs of colored dots sit on a grid. Connect each pair with a pipe so that:

1. **Every pair is connected** by a single continuous path.
2. **Paths never cross** each other.
3. **Every cell is filled** (the "100% flow" / zero-coverage rule).

That third rule is what makes it hard — and what makes a valid solution usually unique.

See **[docs/01-problem-analysis.md](docs/01-problem-analysis.md)** for full rules and why this is
NP-complete.

---

## Level types: one graph, every variant

Flow Free ships ~30 pack categories — Mania, Bridges, Walls, Obstacles, Cubes, Rectangle, Links,
Warps, Hexes. They look like different games. **They aren't.** Every one is the same puzzle on a
different **graph**:

```
Board = (Nodes, Edges, Bridges)
  Nodes  playable cells.        Coverage: every NODE must be filled.
  Edges  legal moves.           A flow may only step along an Edge.
```

| Variant | Transform on the graph |
|---|---|
| **[Mania](docs/levels/01-mania.md)** (up to 15×15) | none — just bigger (the *scale* test) |
| **[Rectangle](docs/levels/02-rectangle.md)** | none — `R ≠ C` |
| **[Walls](docs/levels/03-walls.md)** (bold blocking lines) | **remove edges** |
| **[Obstacles](docs/levels/04-obstacles.md)** (holes / shaped boards) | **remove nodes** |
| **[Bridges](docs/levels/05-bridges.md)** (crossovers) | mark dual-pass nodes |
| **[Links](docs/levels/06-links.md)** (Hoop/Loop/Chain) | remove nodes *(or add edges)* |
| **[Cubes](docs/levels/07-cubes.md)** (3D nets) | **add edges** across folds |

**Remove edges, remove nodes, add edges.** Three operations cover the entire game — all applied at
**parse time**, so the solver never changes.

### The distinction that matters: walls vs obstacles

They look alike on screen and are **opposites** to the solver:

| | **Wall** (line *between* cells) | **Obstacle** (cell is *gone*) |
|---|---|---|
| Cells exist? | **yes, both** | **no** |
| Must be covered? | **yes** | **no** |
| Graph op | remove the **edge** | remove the **node** |

Model an obstacle as a wall and the solver chases a cell that can never be filled → **every board
reports unsolvable**. Getting this right at parse time is what makes the solver correct for free.

## Every level type, shown

Each doc in [`docs/levels/`](docs/levels/) carries a **puzzle and its solution**. Uppercase =
endpoint, lowercase = that colour's pipe, `┃`/`━━━` = wall, `#` = hole, `╬` = bridge.

Here's a wall doing its job — flow `A`'s endpoints sit two cells apart on row 2, but the wall
blocks the direct step, so `A` is forced to **detour through the row above**:

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

All 25 cells still get filled — **including both cells touching the wall.** That's what separates a
wall from a hole.

> **The diagrams are generated, not drawn.** A working solver in [`tools/`](tools/) produces each
> one, and an independent verifier re-checks every rule from scratch (paths join their endpoints,
> every step is a legal edge, no cell double-used, every node covered). Run
> `python3 tools/gen_diagrams.py` to regenerate them.
>
> That prototype is also the proof of the design: **seven variants, and the only one that touched
> the search loop was bridges.**

Full breakdown: **[docs/levels/](docs/levels/)**.

---

## How it solves

Two proven families of approach, both documented in
**[docs/02-solving-approaches.md](docs/02-solving-approaches.md)**:

| Approach | Idea | Trade-off |
|----------|------|-----------|
| **Search (A*/DFS)** | Extend one color at a time; prune dead states early. | No dependencies, fast on typical boards, great on mobile. |
| **SAT reduction** | Encode as boolean constraints, hand to a SAT solver. | Elegant, but needs a solver lib + cycle-elimination loop. |

**Our pick: search-first.** A depth-first search that always extends the *most constrained
color*, backed by aggressive pruning:

- **Coverage** — no empty cell may become unreachable (every cell must end up filled).
- **Connectivity** — every unfinished color must still have a free path between its endpoints.
- **No self-touch / no stranded regions** — reject states that wall off a pocket of the board.

This mirrors Matt Zucker's `flow_solver` and the human heuristics below. Rationale for choosing
search over SAT for an on-device tool is in the approaches doc.

### Human heuristics we encode

The same tricks a person uses (great for pruning and for explaining a solution):

- **Corners & edges first** — a corner endpoint has only 2 exits, an edge cell only 3.
- **Forced moves** — a cell with a single free neighbor forces the path.
- **Don't strand cells** — never make a move that isolates an unreachable pocket.
- **Don't hug yourself** — a path shouldn't touch its own body except at the head.
- **Most-constrained color first** — solve the color with the fewest options next.

---

## Repository layout

```
.
├── README.md                    ← you are here
├── tools/                        Prototype solver + diagram generator (Python)
│   ├── flow.py                   Graph model, DFS solver, independent verifier
│   ├── render.py                 ASCII puzzle/solution renderer
│   └── gen_diagrams.py           Regenerates every diagram in docs/levels/
└── docs/
    ├── 01-problem-analysis.md    Rules, complexity, text format
    ├── 02-solving-approaches.md  Search vs SAT, heuristics, our choice
    ├── 03-architecture.md        The mobile screen-aware assistant design
    ├── 04-solver-design.md       Concrete solver: graph model + algorithm
    ├── 05-roadmap.md             Phased build plan
    ├── references.md             Sources and prior art
    └── levels/                   ← one file per level type
        ├── README.md             The universal graph model (start here)
        ├── 01-mania.md           Large boards — the scale test
        ├── 02-rectangle.md       Non-square boards
        ├── 03-walls.md           Blocking lines → remove edges
        ├── 04-obstacles.md       Holes / shaped boards → remove nodes
        ├── 05-bridges.md         Crossovers → dual-pass nodes
        ├── 06-links.md           Hoop / Loop / Chain
        └── 07-cubes.md           3D nets → seam edges
```

> **PDF docs:** the markdown files are the source of truth. Generate PDFs on demand with
> `pandoc docs/01-problem-analysis.md -o out.pdf` (needs `pandoc` + a LaTeX engine). We don't
> commit binaries.

---

## Roadmap (short version)

1. **Solver core** — data model + search solver, CLI that reads a text grid and prints a solution.
2. **Bridges support** — crossover cells in the model and solver.
3. **Grid detection** — from a screenshot to a puzzle model (CV).
4. **Mobile shell** — background service, floating button, screen capture, overlay.
5. **Auto-draw** — trace the solution with accessibility gestures.

Detail and acceptance criteria per phase: **[docs/05-roadmap.md](docs/05-roadmap.md)**.

---

## References

Prior art and sources are collected in **[docs/references.md](docs/references.md)** — Matt
Zucker's solver, the Torvaney SAT write-up, the Columbia ParallelFlow paper, the puzzling.SE
strategy thread, and the puzzle/answer sites.
