package flow

/**
 * The board is a GRAPH, not a grid.
 *
 * Every level variant is a node/edge transform applied here, at parse time:
 *   walls              -> remove edges
 *   obstacles / holes  -> remove nodes
 *   cubes / warps      -> add edges (seams)
 *   bridges            -> mark a node dual-pass
 *
 * The solver never learns which variant it is solving. It walks [adj] and [nodes].
 *
 * TWO INVARIANTS THIS FILE EXISTS TO ENFORCE:
 *   1. Neighbours come from `adj`. NEVER from (r±1, c) arithmetic.
 *      Doing the arithmetic silently ignores walls, seams and portals: no crash,
 *      just wrong answers.
 *   2. Coverage counts `nodes`. NEVER rows*cols.
 *      Otherwise holes leak into the count and EVERY board reports unsolvable,
 *      with a symptom that points nowhere near the cause.
 */

/** A cell, packed into an Int as (row shl 16) or col. Keeps the hot loop allocation-free. */
@JvmInline
value class Cell(val id: Int) {
    constructor(row: Int, col: Int) : this((row shl 16) or (col and 0xFFFF))
    val row: Int get() = id shr 16
    val col: Int get() = id and 0xFFFF
    override fun toString() = "($row,$col)"
}

enum class Dir(val dr: Int, val dc: Int) {
    U(-1, 0), D(1, 0), L(0, -1), R(0, 1);
    val isHorizontal get() = this == L || this == R
    val opposite get() = when (this) { U -> D; D -> U; L -> R; R -> L }
}

/** One directed edge out of a cell. [inDir] is the travel direction on ARRIVAL — a cube seam
 *  can flip it, which is why it is stored rather than assumed equal to [outDir]. */
class Edge(val to: Cell, val outDir: Dir, val inDir: Dir)

class Board(
    val rows: Int,
    val cols: Int,
    val holes: Set<Cell> = emptySet(),
    val walls: Set<Pair<Cell, Cell>> = emptySet(),
    val bridges: Set<Cell> = emptySet(),
    seams: List<Edge2> = emptyList(),
    val endpoints: Map<Char, Pair<Cell, Cell>> = emptyMap(),
) {
    /** An undirected seam, expanded into two directed edges. */
    class Edge2(val a: Cell, val b: Cell, val outDir: Dir, val inDir: Dir)

    val nodes: Set<Cell>
    private val adjacency: Map<Cell, List<Edge>>

    /** Dense index for the hot loop: cell -> 0..n-1, and the adjacency as arrays. */
    val index: HashMap<Cell, Int>
    val cellAt: Array<Cell>
    val adjOf: Array<Array<Edge>>
    val isBridge: BooleanArray

    /**
     * The adjacency the SOLVER actually walks: pure int arrays, no objects, no hashing.
     *
     * The first version looked up `index[edge.to]` — a HashMap probe — inside every flood-fill
     * inner loop. That is millions of hash lookups per solve and it dominated the profile.
     * Node indices are resolved once, here.
     *
     *   nbr[i]     -> neighbour node indices of node i
     *   nbrOut[i]  -> the direction you STEP to reach that neighbour   (Dir.ordinal)
     *   nbrIn[i]   -> the direction you are TRAVELLING on arrival      (Dir.ordinal; a cube seam
     *                 can make this differ from nbrOut)
     */
    val nbr: Array<IntArray>
    val nbrOut: Array<ByteArray>
    val nbrIn: Array<ByteArray>

    val colors: List<Char> get() = endpoints.keys.sorted()

    init {
        val ns = LinkedHashSet<Cell>()
        for (r in 0 until rows) for (c in 0 until cols) {
            val cell = Cell(r, c)
            if (cell !in holes) ns.add(cell)
        }
        nodes = ns

        val walled = HashSet<Long>()
        for ((a, b) in walls) { walled.add(key(a, b)); walled.add(key(b, a)) }

        val m = HashMap<Cell, MutableList<Edge>>()
        for (cell in nodes) m[cell] = ArrayList(4)
        for (cell in nodes) {
            for (d in Dir.entries) {
                val nb = Cell(cell.row + d.dr, cell.col + d.dc)
                if (nb.row !in 0 until rows || nb.col !in 0 until cols) continue
                if (nb !in nodes) continue                    // hole -> node removed
                if (key(cell, nb) in walled) continue         // wall -> edge removed
                m[cell]!!.add(Edge(nb, d, d))
            }
        }
        for (s in seams) {                                    // seam -> edge added
            require(s.a in nodes && s.b in nodes) { "seam touches a hole: ${s.a} ${s.b}" }
            m[s.a]!!.add(Edge(s.b, s.outDir, s.inDir))
        }
        adjacency = m

        index = HashMap(nodes.size * 2)
        cellAt = Array(nodes.size) { Cell(0) }
        nodes.forEachIndexed { i, cell -> index[cell] = i; cellAt[i] = cell }
        adjOf = Array(nodes.size) { i -> adjacency[cellAt[i]]!!.toTypedArray() }
        isBridge = BooleanArray(nodes.size) { cellAt[it] in bridges }

        // resolve every edge to a node index ONCE — the solver never hashes again
        nbr = Array(nodes.size) { i -> IntArray(adjOf[i].size) { j -> index[adjOf[i][j].to]!! } }
        nbrOut = Array(nodes.size) { i ->
            ByteArray(adjOf[i].size) { j -> adjOf[i][j].outDir.ordinal.toByte() }
        }
        nbrIn = Array(nodes.size) { i ->
            ByteArray(adjOf[i].size) { j -> adjOf[i][j].inDir.ordinal.toByte() }
        }

        validate()
    }

    private fun validate() {
        for ((color, pair) in endpoints) {
            require(pair.first in nodes) { "endpoint $color ${pair.first} is a hole or off-board" }
            require(pair.second in nodes) { "endpoint $color ${pair.second} is a hole or off-board" }
            require(pair.first != pair.second) { "endpoint $color has both dots on the same cell" }
            require(pair.first !in bridges && pair.second !in bridges) {
                "endpoint $color sits on a bridge cell — not supported"
            }
        }
    }

    fun adj(cell: Cell): List<Edge> = adjacency[cell] ?: emptyList()
    fun idx(cell: Cell): Int = index[cell] ?: error("$cell is not a node")

    /** Nodes that a flow must fill. A bridge needs BOTH passes, so it counts twice. */
    val coverageUnits: Int = (nodes.size - bridges.size) + bridges.size * 2

    private fun key(a: Cell, b: Cell): Long = (a.id.toLong() shl 32) or (b.id.toLong() and 0xFFFFFFFFL)

    /**
     * True iff EVERY edge joins cells of opposite (r+c)%2 parity.
     *
     * Plain grid adjacency always satisfies this. A SEAM does not: a cube fold can join
     * (0,2) to (2,6) — both even — and that single edge destroys bipartiteness.
     *
     * Derived, never assumed. Assuming it made [parityOk] reject every cube/warp/portal board
     * as unsolvable without searching.
     */
    private val bipartite: Boolean = run {
        for (cell in nodes) {
            val p = (cell.row + cell.col) and 1
            for (e in adjacency[cell]!!) {
                if (((e.to.row + e.to.col) and 1) == p) return@run false
            }
        }
        true
    }

    /**
     * Parity pre-check. O(nodes), runs before any search.
     *
     * Colour the cells like a chessboard by (r+c)%2. On a bipartite board a flow alternates
     * colour with every step, so:
     *
     *    - both endpoints the SAME colour  -> odd cell-count, eats one extra of that colour (±1)
     *    - endpoints on OPPOSITE colours   -> equal number of each                          ( 0)
     *
     * Full coverage means the flows partition every node, so summing over all colours:
     *
     *     (#even - #odd)  ==  S - T
     *        S = colours with BOTH endpoints on even cells
     *        T = colours with BOTH endpoints on odd cells
     *
     * S-T depends only on where the endpoints are, so a mismatch proves the board is unsolvable
     * WITHOUT SEARCHING. Its real value on a phone: it catches a wall misread as a hole (which
     * shifts the node count by one) before a 14x14 search burns a second of battery.
     *
     * Returns true ("no information") when the invariant does not apply:
     *   - BRIDGES: a bridge node is visited twice, so the flows don't partition the nodes.
     *   - SEAMS:   a same-parity edge means the graph isn't bipartite and flows need not alternate.
     * Never reject in those cases — a false "unsolvable" is far worse than a missed early exit.
     */
    fun parityOk(): Boolean {
        if (bridges.isNotEmpty() || !bipartite) return true
        var even = 0; var odd = 0
        for (cell in nodes) if ((cell.row + cell.col) % 2 == 0) even++ else odd++
        var s = 0; var t = 0
        for ((_, pair) in endpoints) {
            val pa = (pair.first.row + pair.first.col) % 2
            val pb = (pair.second.row + pair.second.col) % 2
            if (pa == pb) { if (pa == 0) s++ else t++ }
        }
        return (even - odd) == (s - t)
    }
}
