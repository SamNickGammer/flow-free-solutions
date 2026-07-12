# 04 — Solver Design

Concrete data model and algorithm for the search solver. Language-neutral here; the reference
implementation is plain JVM (Kotlin) so it runs on desktop CLI and inside the app unchanged.

## Data model

```
Puzzle
  rows, cols            grid dimensions
  colors: List<Color>   each with two endpoint cells (r,c)
  bridges: Set<Cell>    crossover cells (empty for plain Flow Free)

Cell = (row, col)

State (mutable during search)
  owner[r][c]           which color occupies a cell (or EMPTY)
  head[color]           current growing tip of each flow (starts at one endpoint)
  done[color]           true once head reached the other endpoint
  emptyCount            cells still unowned  (goal: reaches 0)
```

For **plain** Flow Free, `owner[r][c]` (one color per cell) is enough. For **bridges**, a cell
can host two flows, so switch to an **edge model**: for each pair of adjacent cells store which
color (if any) connects them. A normal cell requires all its used edges to share one color; a
bridge cell allows the horizontal edge-pair and vertical edge-pair to carry *different* colors,
each passing straight through. Build plain-cell first; add the edge model in Phase 2.

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

1. **Head not boxed in** — every not-`done` head has ≥1 legal move (or is adjacent to its
   endpoint). Else FAIL.
2. **Reachability per color** — flood-fill free cells from each head; its endpoint must be
   reachable. Else FAIL.
3. **Coverage** — flood-fill the empty region; every empty cell must be reachable by *some* live
   head. An unreachable empty cell can never be covered → FAIL.
4. **No stranded pocket** — a connected empty region that contains no live head and no needed
   endpoint can never be filled → FAIL.
5. **Deadend empty cell** — a non-endpoint empty cell with <2 free/compatible neighbors can never
   be a degree-2 pass-through → FAIL.

Checks 2–4 share flood fills; compute once per move. They are what turn an exponential search
into a near-linear walk on real levels.

### Move ordering heuristic

Prefer moves that (a) hug walls / other flows (coverage tends to favor the boundary), and
(b) reduce the reachable-empty frontier the least (don't strand). Cheap tie-breaker, not
correctness-critical.

## Bridges adaptation (Phase 2)

- Switch `prune_ok` reachability/coverage to walk the **edge graph** so a bridge cell can be
  traversed both horizontally and vertically by different colors.
- A flow entering a bridge cell **must exit straight** (H→H or V→V); no turning on a bridge.
- Coverage: a bridge cell is "covered" when both its H and V passes are filled.

## Complexity & limits

Worst case is exponential (Numberlink is NP-complete — see
[01-problem-analysis.md](01-problem-analysis.md)), but designed levels with unique solutions
collapse fast under the coverage/connectivity pruning. Target range 5×5–15×15 solves in
milliseconds to sub-second. *(ponytail: single-threaded DFS; add the A\* frontier ordering or
parallel color-splitting only if a real board measurably stalls.)*

## Testing

- **Fixtures**: text-grid puzzles with known solutions (start with the 3×3 and 5×5 from doc 01,
  add 5×5 bridges levels 1–30 from the reference corpus).
- **Self-check**: a `demo()` / `main` that solves each fixture and `assert`s:
  - every color's path connects its two endpoints,
  - no cell owned by two colors (except valid bridge H/V),
  - `emptyCount == 0` (full coverage).
- One runnable check that fails loudly if the search or a prune breaks — no framework needed.

## CLI (Phase 1 deliverable)

```
flowsolve puzzle.txt         # reads text grid, prints solved grid + per-color paths
flowsolve --bridges b.txt    # bridges model
```

Text in/out keeps the solver fully testable on desktop, decoupled from CV and Android.
