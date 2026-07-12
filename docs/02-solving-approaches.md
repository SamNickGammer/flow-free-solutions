# 02 — Solving Approaches

Two well-trodden families, plus the human heuristics that power both. This doc explains each and
justifies our choice.

## A. Search (constraint-guided DFS / A*)

Frame the puzzle as building paths. Extend flows cell-by-cell, backtracking when a state is
provably dead. This is the approach in Matt Zucker's `flow_solver` and most fast solvers.

**Core loop (best-first / DFS):**

1. Pick the **most-constrained color** — the incomplete flow whose head has the fewest legal
   next moves. (Fewest options = least branching = fewest wasted guesses.)
2. Try each legal move for that color's head (advance one cell toward filling the board).
3. After each move, run **pruning checks**. If any fails, backtrack.
4. Repeat until all flows are complete and the board is fully covered.

**A\* framing (optional refinement):** treat it as shortest-path to a goal state.
- *cost-to-come* = cells filled so far.
- *cost-to-go* (heuristic) = number of empty cells remaining.
This makes a best-first search that reaches full-coverage goal states quickly. Plain DFS with
good pruning is usually enough; A* ordering helps on the biggest boards.

### The pruning checks (this is where the speed comes from)

- **Coverage / no orphan cell** — flood-fill the empty region. If any empty cell can't be
  reached by *some* color that still needs to grow, the state is dead. (A cell nothing can ever
  reach can never be covered.)
- **Connectivity** — for every incomplete color, DFS from its head through free cells; if it
  can't reach its other endpoint, dead.
- **No stranded pocket** — if a move splits the free space into a region that no live flow can
  enter, dead.
- **No self-adjacency** — a flow's head must not sit next to its own body except where it just
  came from (prevents useless loops and speeds convergence).
- **Forced moves** — if the head (or an empty cell) has exactly one free neighbor, that move is
  forced; take it without branching.
- **Deadend cells** — an empty cell with <2 free/endpoint neighbors can never be a degree-2
  pass-through → dead (unless it's an endpoint).

These are cheap (linear-ish flood fills) and kill the vast majority of branches early.

## B. SAT reduction

Encode the board as boolean constraints and let an off-the-shelf SAT solver decide. Two known
encodings:

**Edge-coloring (Torvaney):** model the grid as a graph; each *edge* between adjacent cells gets
`K` boolean vars ("this edge is color c"). Constraints:
- Each used edge has exactly one color.
- Each non-endpoint node has exactly two same-colored incident edges (in + out).
- Each endpoint node has exactly one incident colored edge.

**Cell-based + cycle elimination (Zucker's SAT variant):** vars for cell colors and pipe
directions; solve, then check for **disconnected cycles** (a loop of one color not touching its
endpoints). If found, add a clause forbidding that cycle and re-solve. Iterate until clean.

SAT is elegant and provably complete, but:
- Needs a SAT solver dependency (PicoSAT/pycosat, SAT4J, MiniSat).
- The cycle-elimination loop means multiple solve passes.
- Harder to ship inside a lightweight mobile app and harder to explain step-by-step.

## C. Human heuristics (encode as pruning + for explanations)

From the puzzling.SE strategy thread and common play:

- **Start in corners** — a corner endpoint has one forced first move; a corner *empty* cell has
  only two neighbors, so its two pipe directions are nearly forced.
- **Work the edges** — edge cells have 3 neighbors, interior 4; edges constrain faster.
- **Fill, don't wander** — because of full-coverage, the "long way round" is often correct;
  a path that leaves a gap next to a wall is usually wrong.
- **Don't strand** — never seal off a region smaller than what's left to fill.
- **Don't touch yourself** — flows that run parallel to their own body waste cells.
- **Count parity / leftover cells** — if a color *must* consume an odd/even count to make
  coverage work, that prunes options.

These double as **explanation text** — when the app shows a solution, it can narrate *why* each
early move was forced.

## Our choice: search-first

For an **on-device, dependency-light, explainable** tool, **search with connectivity + coverage
pruning wins**:

| Criterion | Search | SAT |
|-----------|:------:|:---:|
| No external solver dependency | ✅ | ❌ |
| Single solve pass | ✅ | ❌ (cycle loop) |
| Fast on 5×5–15×15 designed levels | ✅ | ✅ |
| Easy to port to Kotlin/native for mobile | ✅ | ⚠️ |
| Step-by-step explanation | ✅ | ⚠️ |

We implement the search solver first (Phase 1). SAT stays a documented fallback if a
pathological board ever stalls the search. Bridges support (Phase 2) extends the *model* (edges,
crossover cells) but keeps the same search+prune engine.

Concrete data model and algorithm: **[04-solver-design.md](04-solver-design.md)**.
