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

**Bridges** variant adds crossover cells where two pipes pass over/under each other on the same
cell. See **[docs/01-problem-analysis.md](docs/01-problem-analysis.md)** for full rules,
the bridges model, and why this is NP-complete.

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
└── docs/
    ├── 01-problem-analysis.md    Rules, bridges variant, complexity
    ├── 02-solving-approaches.md  Search vs SAT, heuristics, our choice
    ├── 03-architecture.md        The mobile screen-aware assistant design
    ├── 04-solver-design.md       Concrete solver: data model + algorithm
    ├── 05-roadmap.md             Phased build plan
    └── references.md             Sources and prior art
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
