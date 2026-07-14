package flow

/**
 * DFS with most-constrained-colour selection, forced-move propagation, and five prunes.
 *
 * All state is on primitive arrays and all pruning reuses scratch buffers, so a solve
 * allocates essentially nothing after construction. That matters on Android: this runs on
 * a background thread while a game is on screen, and GC churn during a solve is exactly
 * what makes an assistant feel janky.
 *
 * Colour lives on the CELL for normal nodes, and per-AXIS on a bridge node — which is what
 * lets two flows cross one cell. See [Board].
 */

const val EMPTY = (-1).toByte()
private const val MAX_BRIDGE_CHAIN = 8

// Dir.ordinal, inlined so the hot loop compares ints instead of touching enum objects
private val DIR_L = Dir.L.ordinal
private val DIR_R = Dir.R.ordinal

class Solution(
    val paths: Map<Char, List<Cell>>,
    val nodesExplored: Long,
    val elapsedMs: Long,
)

class Solver(private val b: Board) {

    private val n = b.nodes.size
    private val colors = b.colors
    private val k = colors.size

    // occupancy
    private val owner = ByteArray(n) { EMPTY }        // normal cells
    private val bridgeH = ByteArray(n) { EMPTY }      // bridge: horizontal pass
    private val bridgeV = ByteArray(n) { EMPTY }      // bridge: vertical pass

    private val head = IntArray(k)
    private val target = IntArray(k)
    private val done = BooleanArray(k)
    private val path = Array(k) { ArrayList<Cell>(n) }

    private var filled = 0                            // coverage units consumed
    private var explored = 0L
    private var active = -1                           // colour currently being routed to completion

    // scratch for flood fills — reused, never reallocated
    private val stamp = IntArray(n)
    private var stampGen = 0
    private val stack = IntArray(n)

    // second stamp plane + a "some colour can fill this" plane, for the strong coverage prune
    private val stamp2 = IntArray(n)
    private var stamp2Gen = 0
    private val usable = IntArray(n)
    private var usableGen = 0
    private val liveFlag = IntArray(n)
    private var liveGen = 0

    // move-ordering scratch (a node has at most 4 candidate moves, +seams)
    private val ordKey = IntArray(8)
    private val ordEdge = IntArray(8)

    // free-space regions, rebuilt once per node
    private val region = IntArray(n)
    private val curMask = LongArray(n)
    private val goalMask = LongArray(n)

    init {
        require(k <= 64) { "more than 64 colours is not supported (bitmask limit)" }
    }

    /** Label the connected components of free space. Returns the region count. O(n). */
    private fun buildRegions(): Int {
        java.util.Arrays.fill(region, -1)
        var rc = 0
        for (i in 0 until n) {
            if (!passable(i) || region[i] >= 0) continue
            var sp = 0
            stack[sp++] = i
            region[i] = rc
            while (sp > 0) {
                val cur = stack[--sp]
                val ns = b.nbr[cur]
                for (x in ns.indices) {
                    val j = ns[x]
                    if (passable(j) && region[j] < 0) { region[j] = rc; stack[sp++] = j }
                }
            }
            rc++
        }
        return rc
    }

    // undo journal
    private class Move(val color: Int, val from: Int, val to: Int,
                       val bridgesCrossed: IntArray, val axes: IntArray, val wasDone: Boolean)

    init {
        colors.forEachIndexed { ci, c ->
            val (a, z) = b.endpoints[c]!!
            val ai = b.idx(a); val zi = b.idx(z)
            owner[ai] = ci.toByte(); owner[zi] = ci.toByte()

            // GROW FROM THE MORE CONSTRAINED END.
            // A corner endpoint has 2 exits, an edge one 3, an interior one 4. Starting from the
            // tighter end front-loads the forced decisions and keeps the branching factor down.
            // Without this the search tree depends on which endpoint the PARSER happened to see
            // first (grid reading order) — i.e. on nothing at all.
            val (h, t) = if (b.adjOf[ai].size <= b.adjOf[zi].size) ai to zi else zi to ai
            head[ci] = h
            target[ci] = t
            path[ci].add(b.cellAt[h])
            filled += 2
        }
    }

    /** Thrown when the solver hits its budget. Distinct from "unsolvable" — we simply gave up. */
    class GaveUp(val nodesExplored: Long, val elapsedMs: Long) :
        RuntimeException("gave up after $nodesExplored nodes / ${elapsedMs}ms")

    private var deadlineNs = Long.MAX_VALUE

    /**
     * @param budgetMs wall-clock budget. On a phone a solve must never spin forever — the user is
     *   staring at a spinner over their game. Exceeding it throws [GaveUp], which the caller shows
     *   as "couldn't solve this one" rather than silently hanging.
     */
    fun solve(nodeCap: Long = 200_000_000L, budgetMs: Long = Long.MAX_VALUE): Solution? {
        val t0 = System.nanoTime()
        deadlineNs = if (budgetMs == Long.MAX_VALUE) Long.MAX_VALUE
                     else t0 + budgetMs * 1_000_000
        if (!b.parityOk()) return null                // free rejection, no search
        val ok = try {
            dfs(nodeCap)
        } catch (e: GaveUp) {
            throw GaveUp(explored, (System.nanoTime() - t0) / 1_000_000)
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (!ok) return null
        val out = HashMap<Char, List<Cell>>()
        colors.forEachIndexed { ci, c ->
            // The search may have grown the flow from EITHER endpoint (see head-orientation in
            // init). Normalise so path[0] is always board.endpoints[c].first — downstream code
            // (renderer, gesture auto-draw) then has one stable contract instead of two.
            val p = ArrayList(path[ci])
            if (p.first() != b.endpoints[c]!!.first) p.reverse()
            out[c] = p
        }
        return Solution(out, explored, ms)
    }

    private fun dfs(cap: Long): Boolean {
        if (++explored > cap) throw GaveUp(explored, 0)
        // check the clock rarely — System.nanoTime() in the hot loop would itself be the bottleneck
        if (explored and 0xFFF == 0L && System.nanoTime() > deadlineNs) throw GaveUp(explored, 0)

        // Forced moves are applied here and must be rolled back if THIS subtree fails.
        // NB: they must NOT be rolled back on success — an earlier version undid them in a
        // `finally`, which unwound the answer on the way out and produced paths that no longer
        // reached their endpoints.
        val forced = ArrayList<Move>(4)

        // ---- forced-move propagation: take every single-option move without branching.
        // pruneOk() is the expensive call, so run it exactly ONCE per iteration — an earlier
        // version also re-ran it after the loop, doubling the cost of every node for nothing.
        while (true) {
            if (!pruneOk()) return fail(forced)
            val ci = findForcedColor()
            if (ci < 0) break
            val ei = onlyMove(ci)
            val m = applyMove(ci, ei) ?: return fail(forced)
            forced.add(m)
        }

        // ---- ROUTE ONE COLOUR TO COMPLETION BEFORE STARTING THE NEXT.
        //
        // This is the single biggest win in the whole solver. Re-picking the most-constrained
        // colour at EVERY node grows all the flows interleaved, and the space of interleaved
        // partial paths is enormously larger than the space of "colours 1..i finished, colour
        // i+1 in progress, the rest untouched".
        //
        // Both are complete — the order you route the colours in cannot make a solvable board
        // unsolvable — so the interleaving buys nothing and costs everything. With it, a real
        // extreme 9x9 burned 2.4M nodes and never finished. Without it, see the benchmark.
        //
        // We still CHOOSE the next colour by most-constrained; we just stick with it.
        var live = active
        if (live >= 0 && done[live]) live = -1
        if (live < 0) {
            var best = Int.MAX_VALUE
            for (ci in 0 until k) {
                if (done[ci]) continue
                val cnt = moveCount(ci)
                if (cnt == 0) return fail(forced)
                if (cnt < best) { best = cnt; live = ci }
            }
        } else if (moveCount(live) == 0) {
            return fail(forced)
        }

        // ---- goal
        if (live == -1) {
            if (filled == b.coverageUnits) return true      // SUCCESS: leave the state intact
            return fail(forced)
        }

        // ---- branch: keep extending THIS colour until it reaches its target
        //
        // MOVE ORDERING. Trying U,D,L,R blindly makes DFS wander through open space and shred
        // the free region into unfillable pockets. Prefer the most CONSTRAINED destination —
        // the one with fewest free neighbours — i.e. hug walls and existing pipes. That is the
        // same instinct a human plays with, and because full coverage means the long way round
        // is usually right, it is not just an aesthetic.
        //
        // Ordering only: it cannot make a solvable board unsolvable.
        val prevActive = active
        active = live
        val h = head[live]
        val deg = b.nbr[h].size

        var cnt = 0
        for (ei in 0 until deg) {
            val to = walk(live, h, ei)
            if (to < 0) continue
            var freeNbrs = 0
            val ns = b.nbr[to]
            for (x in ns.indices) if (passable(ns[x])) freeNbrs++
            // finishing the flow sorts first; otherwise fewest free neighbours first
            ordKey[cnt] = if (to == target[live]) -1 else freeNbrs
            ordEdge[cnt] = ei
            cnt++
        }
        // insertion sort — cnt is at most 4
        for (i in 1 until cnt) {
            val kk = ordKey[i]; val ee = ordEdge[i]
            var j = i - 1
            while (j >= 0 && ordKey[j] > kk) { ordKey[j + 1] = ordKey[j]; ordEdge[j + 1] = ordEdge[j]; j-- }
            ordKey[j + 1] = kk; ordEdge[j + 1] = ee
        }
        val order = IntArray(cnt) { ordEdge[it] }

        for (i in 0 until cnt) {
            val m = applyMove(live, order[i]) ?: continue
            if (dfs(cap)) return true                        // SUCCESS: do not undo
            undo(m)
        }
        active = prevActive
        return fail(forced)
    }

    /** Roll back this frame's forced moves and report failure. */
    private fun fail(forced: ArrayList<Move>): Boolean {
        for (i in forced.indices.reversed()) undo(forced[i])
        return false
    }

    /** A colour whose head has exactly one legal move, or -1. Collapses long corridors for free. */
    private fun findForcedColor(): Int {
        for (ci in 0 until k) {
            if (done[ci]) continue
            var cnt = 0
            val deg = b.nbr[head[ci]].size
            for (ei in 0 until deg) if (legal(ci, ei)) { cnt++; if (cnt > 1) break }
            if (cnt == 1) return ci
        }
        return -1
    }

    private fun onlyMove(ci: Int): Int {
        val deg = b.nbr[head[ci]].size
        for (ei in 0 until deg) if (legal(ci, ei)) return ei
        error("onlyMove called on a colour with no legal move")
    }

    private fun moveCount(ci: Int): Int {
        var cnt = 0
        val deg = b.nbr[head[ci]].size
        for (ei in 0 until deg) if (legal(ci, ei)) cnt++
        return cnt
    }

    // walk() results, written into preallocated fields. This is the hottest path in the solver —
    // returning a fresh IntArray here allocated millions of times on a 14x14 board.
    private val wBridge = IntArray(MAX_BRIDGE_CHAIN)
    private val wAxis = IntArray(MAX_BRIDGE_CHAIN)
    private var wCount = 0

    /** Can colour [ci] step along its head's edge [ei]? Slides straight through bridge chains. */
    private fun legal(ci: Int, ei: Int): Boolean = walk(ci, head[ci], ei) >= 0

    /**
     * Follow [e] out of the head, sliding straight through bridges (a flow entering a bridge
     * MUST exit the far side — no turning on a bridge).
     *
     * Returns the landing node index, or -1 if the move is illegal. Bridges crossed on the way
     * are left in [wBridge]/[wAxis]/[wCount] — valid only until the next walk() call.
     */
    private fun walk(ci: Int, from: Int, ei: Int): Int {
        var to = b.nbr[from][ei]
        var dir = b.nbrIn[from][ei].toInt()
        wCount = 0

        while (b.isBridge[to]) {
            val horiz = dir == DIR_L || dir == DIR_R
            val lane = if (horiz) bridgeH else bridgeV
            if (lane[to] != EMPTY) return -1                       // that axis already used
            if (wCount >= MAX_BRIDGE_CHAIN) return -1
            wBridge[wCount] = to
            wAxis[wCount] = if (horiz) 0 else 1
            wCount++
            // continue STRAIGHT through
            var nxt = -1
            val outs = b.nbrOut[to]
            for (j in outs.indices) if (outs[j].toInt() == dir) { nxt = j; break }
            if (nxt < 0) return -1                                 // bridge with no far side
            dir = b.nbrIn[to][nxt].toInt()
            to = b.nbr[to][nxt]
        }

        if (to == target[ci] && !done[ci]) return to               // closing the flow
        if (owner[to] != EMPTY) return -1
        return to
    }

    private fun applyMove(ci: Int, ei: Int): Move? {
        val from0 = head[ci]
        val to = walk(ci, from0, ei)
        if (to < 0) return null
        val crossed = wCount
        val brs = IntArray(crossed) { wBridge[it] }
        val axs = IntArray(crossed) { wAxis[it] }

        val from = head[ci]
        val wasDone = done[ci]

        for (i in 0 until crossed) {
            val br = brs[i]
            if (axs[i] == 0) bridgeH[br] = ci.toByte() else bridgeV[br] = ci.toByte()
            path[ci].add(b.cellAt[br])
            filled++
        }
        path[ci].add(b.cellAt[to])
        if (to == target[ci]) {
            done[ci] = true                            // endpoint was already counted as filled
        } else {
            owner[to] = ci.toByte()
            filled++
        }
        head[ci] = to
        return Move(ci, from, to, brs, axs, wasDone)
    }

    private fun undo(m: Move) {
        val ci = m.color
        head[ci] = m.from
        if (m.to == target[ci]) {
            done[ci] = m.wasDone
        } else {
            owner[m.to] = EMPTY
            filled--
        }
        path[ci].removeAt(path[ci].size - 1)
        for (i in m.bridgesCrossed.indices.reversed()) {
            val br = m.bridgesCrossed[i]
            if (m.axes[i] == 0) bridgeH[br] = EMPTY else bridgeV[br] = EMPTY
            path[ci].removeAt(path[ci].size - 1)
            filled--
        }
    }

    /** A node still able to take a flow. Relaxed on bridges (either axis free) — relaxed is SOUND:
     *  it may fail to prune, but it never prunes a state that was actually solvable. */
    private fun passable(i: Int): Boolean =
        if (b.isBridge[i]) bridgeH[i] == EMPTY || bridgeV[i] == EMPTY else owner[i] == EMPTY

    /**
     * The prunes. All of them walk NODES and EDGES — which is exactly why walls, holes and
     * seams need no special-casing anywhere in this file.
     */
    private fun pruneOk(): Boolean {
        // 1. every live head still has a move
        var anyLive = false
        for (ci in 0 until k) {
            if (done[ci]) continue
            anyLive = true
            if (moveCount(ci) == 0) return false
        }
        if (!anyLive) return filled == b.coverageUnits

        // 2 + 3. STRANDED CHECK, via free-space REGIONS.
        //
        // This replaced a version that ran TWO flood fills PER COLOUR per node — O(k * n * deg),
        // about 50,000 operations a node. It was ~73 microseconds/node and no amount of pruning
        // could pay for that. The regions are built ONCE (O(n)) and every colour is then answered
        // by cheap adjacency lookups.
        //
        //   - a live colour must have SOME free region touching BOTH its head and its target,
        //     otherwise the path can never get from one to the other;
        //   - every free region must be touched by the head AND target of at least one live
        //     colour, otherwise nothing can ever fill it.
        //
        // (A head sitting directly next to its own target needs no region at all.)
        val rc = buildRegions()

        for (r in 0 until rc) { curMask[r] = 0L; goalMask[r] = 0L }

        var adjacentDone = 0L                         // colours whose head already touches target
        for (ci in 0 until k) {
            if (done[ci]) continue
            val bit = 1L shl ci
            val h = head[ci]
            val t = target[ci]

            val hn = b.nbr[h]
            for (x in hn.indices) {
                val j = hn[x]
                if (j == t) adjacentDone = adjacentDone or bit
                if (passable(j)) curMask[region[j]] = curMask[region[j]] or bit
            }
            val tn = b.nbr[t]
            for (x in tn.indices) {
                val j = tn[x]
                if (passable(j)) goalMask[region[j]] = goalMask[region[j]] or bit
            }
        }

        // no region joins this colour's head to its target -> stranded colour
        for (ci in 0 until k) {
            if (done[ci]) continue
            val bit = 1L shl ci
            if (adjacentDone and bit != 0L) continue
            var ok = false
            for (r in 0 until rc) {
                if (curMask[r] and bit != 0L && goalMask[r] and bit != 0L) { ok = true; break }
            }
            if (!ok) return false
        }

        // a free region no colour can enter AND leave -> can never be filled
        for (r in 0 until rc) {
            if (curMask[r] and goalMask[r] == 0L) return false
        }

        // 4. deadend: a free non-endpoint node needs >= 2 usable edges to be a degree-2 pass-through
        //    (live head/target flags are stamped once, not re-scanned O(k) per neighbour)
        liveGen++
        for (ci in 0 until k) {
            if (done[ci]) continue
            liveFlag[head[ci]] = liveGen
            liveFlag[target[ci]] = liveGen
        }
        for (i in 0 until n) {
            if (b.isBridge[i] || owner[i] != EMPTY) continue
            var deg = 0
            val ns = b.nbr[i]
            for (x in ns.indices) {
                val j = ns[x]
                if (passable(j) || liveFlag[j] == liveGen) { deg++; if (deg >= 2) break }
            }
            if (deg < 2) return false
        }
        return true
    }

    /**
     * Flood fill over EDGES from [from] across passable nodes, marking [plane] with [gen].
     * [extra] is always traversable (used to let a flood reach its own occupied endpoint).
     */
    private fun flood(from: Int, extra: Int, plane: IntArray, gen: Int) {
        var sp = 0
        stack[sp++] = from
        plane[from] = gen
        while (sp > 0) {
            val cur = stack[--sp]
            val ns = b.nbr[cur]
            for (x in ns.indices) {
                val j = ns[x]
                if (plane[j] == gen) continue
                if (!passable(j) && j != extra) continue
                plane[j] = gen
                stack[sp++] = j
            }
        }
    }
}
