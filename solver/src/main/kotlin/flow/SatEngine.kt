package flow

import org.sat4j.core.VecInt
import org.sat4j.minisat.SolverFactory
import org.sat4j.specs.ContradictionException
import org.sat4j.specs.TimeoutException

/**
 * SAT engine — the one that actually works.
 *
 * WHY NOT SEARCH. The DFS in [Solver] is correct and passes every variant test, but it cannot
 * solve real levels. Measured against the real Flow Free corpus: a 12x12 Extreme burned
 * 85,000,000 nodes in 90 seconds and never finished. Regular packs up to 9x9 are instant;
 * everything past that falls off a cliff. Adding forced-move propagation, region-based stranded
 * pruning, most-constrained-colour selection and wall-hugging move ordering each helped, and none
 * of it closed a gap that is orders of magnitude wide.
 *
 * This is not a novel finding: Matt Zucker wrote the canonical Flow Free search solver, then wrote
 * a follow-up titled "eating SAT-flavored crow" when his SAT version beat it. Our docs originally
 * chose search over SAT. That call was wrong, and the benchmark is what proved it.
 *
 * ENCODING (Torvaney / Zucker, edge-based):
 *
 *   X(i,c)  cell i has colour c
 *   Y(e)    edge e carries a pipe
 *
 *   1. every cell has EXACTLY ONE colour
 *   2. endpoint cells have their given colour (unit clause)
 *   3. degree: an endpoint has EXACTLY ONE incident pipe; every other cell EXACTLY TWO
 *      (this is the "pipe in, pipe out" rule, and it is also what forces full coverage —
 *      a cell with no pipe cannot satisfy degree 2)
 *   4. a pipe joins cells of the SAME colour:  Y(e) -> (X(u,c) <-> X(v,c))
 *
 * Those constraints force every colour class to be a disjoint union of paths and cycles, with the
 * two endpoints the only degree-1 cells. So the endpoint component is always a correct path — but
 * a colour may ALSO produce free-floating CYCLES, which are not a legal Flow Free solution.
 *
 * CYCLE ELIMINATION: solve, look for cycles, add a clause forbidding that exact ring of edges,
 * solve again. Repeat. In practice this converges in a handful of rounds.
 *
 * VARIANTS come free, because the encoding is built from the graph:
 *   walls  -> the edge variable never exists
 *   holes  -> the cell is not a node
 *   seams  -> just another edge (cube folds, warps, portals)
 *   bridges-> NOT handled here; a bridge cell carries two colours at once and breaks
 *             "every cell has exactly one colour". Bridges fall back to [Solver], which handles
 *             them correctly and only ever sees small boards.
 */
object SatEngine {

    fun supports(b: Board): Boolean = b.bridges.isEmpty() && b.colors.size <= 24

    /** Undirected edge list built from the graph — walls are absent, seams are present. */
    private class Edges(b: Board) {
        val u = ArrayList<Int>()
        val v = ArrayList<Int>()
        val incident: Array<MutableList<Int>> = Array(b.nodes.size) { mutableListOf() }

        init {
            val seen = HashSet<Long>()
            for (i in 0 until b.nodes.size) {
                for (j in b.nbr[i]) {
                    val a = minOf(i, j); val z = maxOf(i, j)
                    val key = a.toLong() * 100_000L + z
                    if (!seen.add(key)) continue
                    val e = u.size
                    u.add(a); v.add(z)
                    incident[a].add(e); incident[z].add(e)
                }
            }
        }
        val size get() = u.size
    }

    fun solve(b: Board, budgetMs: Long = 20_000): Solution? {
        require(supports(b)) { "SatEngine does not handle bridges" }
        val t0 = System.nanoTime()

        val n = b.nodes.size
        val colors = b.colors
        val k = colors.size
        val edges = Edges(b)

        // variable numbering (1-based, as SAT requires)
        fun x(i: Int, c: Int) = 1 + i * k + c
        fun y(e: Int) = 1 + n * k + e
        val nVars = n * k + edges.size

        val solver = SolverFactory.newDefault()
        solver.newVar(nVars)
        solver.setTimeoutMs(budgetMs)

        val endpointColor = IntArray(n) { -1 }
        colors.forEachIndexed { ci, ch ->
            val (a, z) = b.endpoints[ch]!!
            endpointColor[b.idx(a)] = ci
            endpointColor[b.idx(z)] = ci
        }

        try {
            // 1. every cell has exactly one colour
            for (i in 0 until n) {
                val atLeast = IntArray(k) { c -> x(i, c) }
                solver.addClause(VecInt(atLeast))
                for (c1 in 0 until k) for (c2 in c1 + 1 until k) {
                    solver.addClause(VecInt(intArrayOf(-x(i, c1), -x(i, c2))))
                }
            }

            // 2. endpoints have their given colour
            for (i in 0 until n) {
                val c = endpointColor[i]
                if (c >= 0) solver.addClause(VecInt(intArrayOf(x(i, c))))
            }

            // 3. degree — endpoints exactly 1 pipe, everything else exactly 2
            for (i in 0 until n) {
                val inc = edges.incident[i]
                val want = if (endpointColor[i] >= 0) 1 else 2
                if (inc.size < want) return null          // cannot possibly satisfy: dead board
                exactly(solver, inc.map { y(it) }, want)
            }

            // 4. a pipe joins two cells of the same colour
            for (e in 0 until edges.size) {
                val a = edges.u[e]; val z = edges.v[e]
                for (c in 0 until k) {
                    solver.addClause(VecInt(intArrayOf(-y(e), -x(a, c), x(z, c))))
                    solver.addClause(VecInt(intArrayOf(-y(e), -x(z, c), x(a, c))))
                }
            }
        } catch (e: ContradictionException) {
            return null                                   // trivially unsatisfiable
        }

        // ---- solve, eliminating cycles until the answer is clean
        var rounds = 0
        while (true) {
            rounds++
            val sat = try {
                solver.isSatisfiable()
            } catch (e: TimeoutException) {
                throw Solver.GaveUp(rounds.toLong(), (System.nanoTime() - t0) / 1_000_000)
            }
            if (!sat) return null                         // genuinely unsolvable

            val model = solver.model()
            val truth = BooleanArray(nVars + 1)
            for (lit in model) if (lit > 0) truth[lit] = true

            val used = ArrayList<Int>()
            for (e in 0 until edges.size) if (truth[y(e)]) used.add(e)

            // adjacency of the pipe graph
            val pipe = Array(n) { mutableListOf<Int>() }
            for (e in used) { pipe[edges.u[e]].add(edges.v[e]); pipe[edges.v[e]].add(edges.u[e]) }

            // walk each colour's path from one endpoint to the other
            val onPath = BooleanArray(n)
            val paths = HashMap<Char, List<Cell>>()
            for ((ci, ch) in colors.withIndex()) {
                val (aC, zC) = b.endpoints[ch]!!
                val a = b.idx(aC); val z = b.idx(zC)
                val p = ArrayList<Cell>()
                var prev = -1
                var cur = a
                while (true) {
                    p.add(b.cellAt[cur])
                    onPath[cur] = true
                    if (cur == z) break
                    val nxt = pipe[cur].firstOrNull { it != prev }
                        ?: error("colour $ch: pipe path dead-ends — encoding bug")
                    prev = cur
                    cur = nxt
                }
                paths[ch] = p
            }

            // any cell NOT on a path is part of a free-floating cycle — forbid that ring and retry
            val cycles = ArrayList<List<Int>>()
            val seen = BooleanArray(n)
            for (i in 0 until n) {
                if (onPath[i] || seen[i]) continue
                // collect this cycle's edges
                val ring = ArrayList<Int>()
                val stack = ArrayDeque<Int>()
                stack.add(i); seen[i] = true
                val members = HashSet<Int>()
                while (stack.isNotEmpty()) {
                    val cur = stack.removeLast()
                    members.add(cur)
                    for (nb in pipe[cur]) if (!seen[nb] && !onPath[nb]) { seen[nb] = true; stack.add(nb) }
                }
                for (e in used) {
                    if (edges.u[e] in members && edges.v[e] in members) ring.add(e)
                }
                if (ring.isNotEmpty()) cycles.add(ring)
            }

            if (cycles.isEmpty()) {
                val ms = (System.nanoTime() - t0) / 1_000_000
                return Solution(paths, rounds.toLong(), ms)
            }

            // forbid each cycle: at least one of its edges must NOT be a pipe
            try {
                for (ring in cycles) {
                    solver.addClause(VecInt(IntArray(ring.size) { -y(ring[it]) }))
                }
            } catch (e: ContradictionException) {
                return null
            }
        }
    }

    /** Exactly [m] of [lits] are true. |lits| <= ~6, so brute-force subsets is fine and exact. */
    private fun exactly(solver: org.sat4j.specs.ISolver, lits: List<Int>, m: Int) {
        val sz = lits.size
        // at most m  : every (m+1)-subset has at least one FALSE
        combos(sz, m + 1) { pick ->
            solver.addClause(VecInt(IntArray(pick.size) { -lits[pick[it]] }))
        }
        // at least m : every (sz-m+1)-subset has at least one TRUE
        combos(sz, sz - m + 1) { pick ->
            solver.addClause(VecInt(IntArray(pick.size) { lits[pick[it]] }))
        }
    }

    private inline fun combos(n: Int, r: Int, action: (IntArray) -> Unit) {
        if (r <= 0 || r > n) return
        val idx = IntArray(r) { it }
        while (true) {
            action(idx)
            var i = r - 1
            while (i >= 0 && idx[i] == n - r + i) i--
            if (i < 0) return
            idx[i]++
            for (j in i + 1 until r) idx[j] = idx[j - 1] + 1
        }
    }
}

/**
 * The public entry point. Picks the engine that can actually do the job:
 *   - SAT for everything it supports (all real levels; walls, holes, seams, rectangles, mania)
 *   - the DFS [Solver] for bridges, which SAT's one-colour-per-cell encoding cannot express
 *     (and which only ever appear on small boards)
 */
object Flow {
    fun solve(b: Board, budgetMs: Long = 20_000): Solution? =
        if (SatEngine.supports(b)) SatEngine.solve(b, budgetMs)
        else Solver(b).solve(budgetMs = budgetMs)
}
