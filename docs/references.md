# References & Prior Art

Sources that informed the analysis and design.

## Solvers & write-ups

- **Matt Zucker — Flow Free solver** (`flow_solver`, single C/Python file). Fast search solver
  with most-constrained-color selection and connectivity/coverage pruning; also a SAT variant
  with cycle elimination. Our search approach mirrors this.
  - https://mzucker.github.io/2016/08/28/flow-solver.html
  - https://mzucker.github.io/2016/09/02/eating-sat-flavored-crow.html (SAT follow-up)
  - https://github.com/mzucker/flow_solver

- **Torvaney — "Flow Free: A SATisfying solution."** Clean SAT reduction: model the grid as a
  graph, `K` boolean vars per edge, constraints for one-color edges, degree-2 interior nodes,
  degree-1 endpoints. Basis for our SAT fallback notes.
  - https://torvaney.github.io/projects/flow-solver.html

- **Columbia ParallelFlow report (CSEE 4995).** Parallel Flow Free solver; constraint
  formulation, search, and parallelization. (PDF; binary — read directly.)
  - https://www.cs.columbia.edu/~sedwards/classes/2021/4995-fall/reports/ParallelFlow.pdf

- Other implementations for reference:
  - https://github.com/Kongesque/flow-free-solver (in-browser, local)
  - https://github.com/lohchness/flow-free-solver (graph-based)
  - https://github.com/mithracodes/flow-free (C)
  - https://github.com/dheeraj135/Flow-Free-Solver

## Human strategy

- **puzzling.stackexchange — "Strategy for solving Flow Free puzzles."** Corners/edges first,
  forced moves, don't strand regions, don't self-touch. Encoded as pruning + explanations.
  - https://puzzling.stackexchange.com/questions/47685/strategy-for-solving-flow-free-puzzles

## Puzzle / answer corpora (test fixtures)

- **givemetheanswer — Flow Free answers.** Level solutions; useful to validate the solver.
  - https://givemetheanswer.com/flow-free/
- **givemetheanswer — Flow Free Bridges, 5×5 levels 1–30.** Bridges test corpus for Phase 2.
  - https://givemetheanswer.com/flow-free-bridges/starter-pack/5x5-levels-1-to-30/

## Background

- **Numberlink** — the puzzle family Flow Free belongs to; deciding solvability (incl. the
  cover-all-cells variant) is **NP-complete**. Justifies search + pruning / SAT over any exact
  polynomial method.
