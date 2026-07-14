package flow

/**
 * One text format, every variant. No per-variant flags — a flag per variant would be a flag
 * per graph shape, which is exactly the abstraction the graph model exists to delete.
 *
 *     5 5
 *     B . . . R
 *     . . . . .
 *     . . Y . .
 *     . G . . .
 *     B R Y G .
 *     WALL 1,1 1,2      // blocks the edge between (1,1) and (1,2) — BOTH cells still exist
 *     HOLE 2,3          // cell (2,3) does not exist — excluded from coverage
 *     BRIDGE 3,2        // crossover cell (two flows pass straight through)
 *     SEAM 0,2 2,6 R L  // add an edge between two non-adjacent cells (cube fold / warp / portal)
 *
 * Grid chars: '.' empty · '#' hole · '+' bridge · letter = endpoint.
 * Each letter must appear exactly twice.
 *
 * Comments are '//'. NOT '#' — '#' is the hole glyph, and using it for both silently
 * truncated every grid row containing a hole.
 */
object Parser {

    fun parse(text: String): Board {
        val lines = text.lines()
            .map { it.substringBefore("//").trim() }
            .filter { it.isNotEmpty() }
        require(lines.isNotEmpty()) { "empty puzzle" }

        val dims = lines[0].split(Regex("\\s+"))
        require(dims.size == 2) { "line 1 must be '<rows> <cols>', got: '${lines[0]}'" }
        val rows = dims[0].toIntOrNull() ?: error("bad row count: '${dims[0]}'")
        val cols = dims[1].toIntOrNull() ?: error("bad col count: '${dims[1]}'")
        require(rows > 0 && cols > 0) { "grid must be at least 1x1" }
        require(lines.size > rows) { "expected $rows grid rows after the header, found ${lines.size - 1}" }

        val holes = HashSet<Cell>()
        val bridges = HashSet<Cell>()
        val dots = HashMap<Char, MutableList<Cell>>()

        for (r in 0 until rows) {
            val toks = lines[1 + r].split(Regex("\\s+")).filter { it.isNotEmpty() }
            require(toks.size == cols) {
                "row $r has ${toks.size} cells, expected $cols: '${lines[1 + r]}'"
            }
            for (c in 0 until cols) {
                val t = toks[c]
                require(t.length == 1) { "cell ($r,$c) must be one character, got '$t'" }
                when (val ch = t[0]) {
                    '.' -> {}
                    '#' -> holes.add(Cell(r, c))
                    '+' -> bridges.add(Cell(r, c))
                    else -> {
                        require(ch.isLetter()) {
                            "cell ($r,$c): '$ch' is not '.', '#', '+' or a letter"
                        }
                        // CASE-SENSITIVE. Real Flow Free levels run out of distinct uppercase
                        // letters on big boards and use 'b' as a different colour from 'B'.
                        // Uppercasing here merged them into one 4-dot colour and rejected the
                        // level as malformed.
                        dots.getOrPut(ch) { ArrayList() }.add(Cell(r, c))
                    }
                }
            }
        }

        val walls = HashSet<Pair<Cell, Cell>>()
        val seams = ArrayList<Board.Edge2>()

        for (i in (1 + rows) until lines.size) {
            val toks = lines[i].split(Regex("\\s+"))
            when (toks[0].uppercase()) {
                "WALL" -> {
                    require(toks.size == 3) { "WALL needs 2 cells: 'WALL r,c r,c' — got '${lines[i]}'" }
                    val a = cell(toks[1]); val z = cell(toks[2])
                    val d = Math.abs(a.row - z.row) + Math.abs(a.col - z.col)
                    require(d == 1) { "WALL cells must be adjacent: $a and $z are not" }
                    walls.add(a to z)
                }
                "HOLE" -> {
                    require(toks.size == 2) { "HOLE needs 1 cell: 'HOLE r,c'" }
                    holes.add(cell(toks[1]))
                }
                "BRIDGE" -> {
                    require(toks.size == 2) { "BRIDGE needs 1 cell: 'BRIDGE r,c'" }
                    bridges.add(cell(toks[1]))
                }
                // SEAM r,c r,c <dirA> <dirB>
                //   from A, stepping dirA lands on B.  From B, stepping dirB lands on A.
                //
                // Both directions are given explicitly because a fold is NOT a reversal: on a
                // cube net, both faces exit *rightward* into the same seam (dirA = dirB = R).
                // Auto-deriving the reverse as dirA.opposite produced an edge pointing L out of
                // B — which collides with B's real left-hand neighbour and silently corrupts
                // the graph.
                "SEAM" -> {
                    require(toks.size == 5) { "SEAM needs: 'SEAM r,c r,c <dirA> <dirB>'" }
                    val a = cell(toks[1]); val z = cell(toks[2])
                    val da = Dir.valueOf(toks[3].uppercase())
                    val db = Dir.valueOf(toks[4].uppercase())
                    // arriving at Z, you travel opposite to the way Z would exit back toward A
                    seams.add(Board.Edge2(a, z, da, db.opposite))
                    seams.add(Board.Edge2(z, a, db, da.opposite))
                }
                else -> error("unknown directive '${toks[0]}' on line ${i + 1}")
            }
        }

        require(dots.isNotEmpty()) { "no endpoints found — every puzzle needs at least one pair" }
        val endpoints = HashMap<Char, Pair<Cell, Cell>>()
        for ((ch, cells) in dots) {
            require(cells.size == 2) {
                "colour '$ch' appears ${cells.size} time(s); every colour needs exactly 2 endpoints"
            }
            endpoints[ch] = cells[0] to cells[1]
        }

        return Board(rows, cols, holes, walls, bridges, seams, endpoints)
    }

    private fun cell(tok: String): Cell {
        val p = tok.split(',')
        require(p.size == 2) { "bad cell '$tok', expected 'r,c'" }
        return Cell(p[0].trim().toInt(), p[1].trim().toInt())
    }
}

/**
 * Independent checker. Deliberately does NOT trust the solver — it re-walks each path from
 * scratch and re-derives every rule. If the solver and this ever disagree, one of them is wrong,
 * and that is exactly what we want to find out.
 */
object Verifier {
    fun verify(b: Board, paths: Map<Char, List<Cell>>) {
        val fill = HashMap<Cell, Char>()
        val bridgeFill = HashMap<Cell, MutableMap<Char, Char>>()   // cell -> axis -> colour

        for ((color, p) in paths) {
            val (a, z) = b.endpoints[color] ?: error("solution has unknown colour '$color'")
            check(p.first() == a && p.last() == z) { "$color: path does not join its endpoints" }
            check(p.size >= 2) { "$color: path is degenerate" }

            for (i in 0 until p.size - 1) {
                val from = p[i]; val to = p[i + 1]
                check(b.adj(from).any { it.to == to }) {
                    "$color: step $from -> $to is not an edge (wall or hole violated)"
                }
            }
            for ((i, cell) in p.withIndex()) {
                if (cell in b.bridges) {
                    check(i > 0 && i < p.size - 1) { "$color: path starts/ends on a bridge $cell" }
                    val prev = p[i - 1]; val next = p[i + 1]
                    val axis = when {
                        prev.row == cell.row && next.row == cell.row -> 'H'
                        prev.col == cell.col && next.col == cell.col -> 'V'
                        else -> error("$color: turned on bridge $cell — flows must go straight through")
                    }
                    val lanes = bridgeFill.getOrPut(cell) { HashMap() }
                    check(axis !in lanes) { "bridge $cell: axis $axis used twice" }
                    lanes[axis] = color
                } else {
                    check(cell !in fill) { "cell $cell covered by two flows (${fill[cell]} and $color)" }
                    fill[cell] = color
                }
            }
        }

        // COVERAGE — counts NODES, never rows*cols
        for (cell in b.nodes) {
            if (cell in b.bridges) continue
            check(cell in fill) { "COVERAGE: node $cell left empty" }
        }
        for (cell in b.bridges) {
            val lanes = bridgeFill[cell] ?: error("bridge $cell never crossed")
            check(lanes.keys == setOf('H', 'V')) { "bridge $cell not crossed on both axes" }
        }
        for (h in b.holes) check(h !in fill) { "hole $h was filled" }
    }
}
