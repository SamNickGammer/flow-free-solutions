# 04 — Solver Design

Concrete data model and algorithm for the search solver. Language-neutral here; the reference
implementation is plain JVM (Kotlin) so it runs on desktop CLI and inside the app unchanged.

## Data model — a graph, not a grid

**This is the load-bearing decision in the whole project.** The board is a **graph**, and every
level variant (walls, obstacles, bridges, cubes, rectangles) is a **node/edge transform applied at
parse time**. The solver below never changes. See **[levels/README.md](levels/README.md)**.

```
Board  (immutable, built once at parse time)
  nodes: Set<Cell>              the playable cells.  Coverage = every NODE filled.
  edges: Set<(Cell, Cell)>      legal moves.  A flow may ONLY step along an edge.
  bridges: Set<Cell>            crossover nodes (two straight passes)
  colors: List<Color>           each with two endpoint cells

Cell = (row, col)

State  (mutable during search)
  edgeColor[edge]       which color runs along an edge (or NONE)
  head[color]           growing tip of each flow (starts at one endpoint)
  done[color]           true once head reached the other endpoint
  emptyCount            NODES still unfilled  (goal: reaches 0)
```

### Two rules that make every variant free

> **1. Neighbors come from `edges`. Never from `(r±1, c)` arithmetic.**
>
> If `r+1` or `c-1` appears anywhere in the search or a flood fill, that is a bug. It silently
> breaks walls (removed edges), cubes/warps (added seam edges), and links (portals) — no crash,
> just wrong answers.

> **2. Coverage counts `nodes`. Never `rows × cols`.**
>
> Any `for r in rows: for c in cols:` loop lets obstacle cells leak back into the coverage count,
> and the solver then reports **every board unsolvable** while chasing a cell that cannot exist.

Follow those two and walls, obstacles, rectangles, links and warps all cost **zero solver code**.

### Node consistency rules

| Node type | Constraint on its used edges |
|---|---|
| **Normal** | exactly 2 used edges, both the **same** color (pipe in, pipe out) |
| **Endpoint** | exactly 1 used edge |
| **Bridge** | H pair (L+R) one color, V pair (U+D) a *different* color; both straight through |

Colors live on **edges**, not cells — that is what lets a bridge node carry two flows at once. A
cell-based `owner[r][c]` cannot represent a bridge, which is why we don't build one and retrofit
it later. See [levels/05-bridges.md](levels/05-bridges.md).

## Algorithm (DFS with most-constrained-color + pruning)

```
solve(state):
    if not prune_ok(state):          # dead state → backtrack
        return FAIL
    apply_forced_moves(state)        # take all single-option moves, no branching
    if all colors done:
        return SUCCESS if state.emptyCount == 0 else FAIL   # coverage check
    c = most_constrained_color(state)      # fewest legal head moves
    for each free neighbor n of head[c] (ordered by heuristic):
        push move (c → n)
        if solve(state) == SUCCESS: return SUCCESS
        pop move
    return FAIL
```

### `most_constrained_color`

Among not-`done` colors, pick the one whose head has the **fewest** legal next cells. Ties: pick
the color closest to its target endpoint. This front-loads forced/near-forced decisions and
keeps the branching factor tiny.

### `apply_forced_moves`

Repeatedly: if any head has exactly one legal move, take it. If a head is orthogonally adjacent
to its own target endpoint, connect and mark `done`. Loop until nothing is forced. (Forced-move
propagation alone finishes many small boards with zero backtracking.)

### `prune_ok` — the guardrails (run after every move)

All five walk `nodes` and `edges`. That is why they handle every variant for free.

1. **Head not boxed in** — every not-`done` head has ≥1 legal move (or is adjacent to its
   endpoint). Else FAIL.
2. **Reachability per color** — flood-fill free nodes **along `edges`** from each head; its
   endpoint must be reachable. Else FAIL.
3. **Coverage** — flood-fill the empty region; every empty **node** must be reachable by *some*
   live head. An unreachable empty node can never be covered → FAIL.
4. **No stranded pocket** — a connected empty region containing no live head and no needed
   endpoint can never be filled → FAIL.
5. **Deadend node** — a non-endpoint empty node with <2 usable **edges** can never be a degree-2
   pass-through → FAIL.

Checks 2–4 share flood fills; compute once per move. They are what turn an exponential search
into a near-linear walk on real levels.

**Note how the variants fall out of these for free:** a *wall* is an absent edge, so checks 2/3/5
route around it automatically. An *obstacle* is an absent node, so it never enters check 3's count.
A *cube seam* or *warp* is an extra edge, so checks 2/3 traverse it with no special case. Not one
line of variant-specific pruning code.

### Parity pre-check (free, runs before any search)

The grid is bipartite. Colour cells by `(r+c) % 2`; a flow alternates with every step. For a
full-coverage solution:

```
(#even nodes) − (#odd nodes)  ==  S − T
    S = colors with BOTH endpoints on even cells
    T = colors with BOTH endpoints on odd cells
```

`S − T` is fixed by the endpoints alone, so this is checkable in **O(nodes)** before searching. If
it fails, **the board is unsolvable — don't search.**

Its real value is catching **detector misreads**: mistaking a wall for a hole (or vice versa) shifts
the node count by one and usually trips this immediately, instead of burning a full search on a
14×14 board that was never solvable. Cheap, and it fails in the right place.

*(Does not apply to boards with bridges — a bridge node is visited twice, so the flows no longer
partition the nodes.)*

### Move ordering heuristic

Prefer moves that (a) hug walls / other flows (coverage tends to favor the boundary), and
(b) reduce the reachable-empty frontier the least (don't strand). Cheap tie-breaker, not
correctness-critical.

## Variant support — all at parse time

Because the search walks a graph, adding a variant means **building a different graph**, not
writing solver code:

| Variant | Parse-time transform | Solver change |
|---|---|---|
| Walls | remove edges | none |
| Obstacles / shaped boards | remove nodes | none |
| Rectangle | `rows ≠ cols` | none |
| Mania | bigger `nodes` | none |
| Cubes / Warps / portals | add edges | none |
| **Bridges** | mark crossover nodes | **the one exception** ↓ |

**Bridges** is the only variant that touches the search, because it changes the *node consistency
rule* (a bridge node carries two colors, and a flow entering one must exit straight — a forced
move, no branching). Traversal and coverage must treat a bridge node as crossable **once per
axis**. Everything else is untouched.

Per-variant detail: **[levels/](levels/)**.

## Complexity & limits

Worst case is exponential (Numberlink is NP-complete — see
[01-problem-analysis.md](01-problem-analysis.md)), but designed levels with unique solutions
collapse fast under the coverage/connectivity pruning. Target range 5×5–15×15 solves in
milliseconds to sub-second. *(ponytail: single-threaded DFS; add the A\* frontier ordering or
parallel color-splitting only if a real board measurably stalls.)*

## Testing

- **Fixtures**: text-grid puzzles with known solutions (3×3 and 5×5 from doc 01; then one per
  variant — see each `levels/` doc for what its fixture must prove).
- **Self-check**: a `demo()` / `main` that solves each fixture and `assert`s:
  - every color's path connects its two endpoints, stepping only along `edges`,
  - no node carries two colors (except a valid bridge H/V pair, which must differ),
  - `emptyCount == 0` — every **node** covered (holes excluded).
- One runnable check that fails loudly if the search or a prune breaks — no framework needed.

**The fixtures that actually earn their keep** are the ones where the variant is *load-bearing*:
a board solvable **only because** of a wall, unsolvable if a seam edge is dropped, requiring two
flows through one bridge. A solver that silently ignores walls still passes a fixture whose wall
wasn't on the solution path — so pick fixtures that fail loudly when the transform is skipped.

## CLI (Phase 1 deliverable)

```
flowsolve puzzle.txt      # reads text grid (any variant), prints solved grid + per-color paths
```

**No `--bridges` flag.** The text format (see [levels/README.md](levels/README.md)) carries
`WALL` / `HOLE` / `BRIDGE` directives inline, so one parser and one command handle every variant.
A flag per variant would be a flag per *graph shape* — exactly the abstraction the graph model
exists to delete.

Text in/out keeps the solver fully testable on desktop, decoupled from CV and Android.
