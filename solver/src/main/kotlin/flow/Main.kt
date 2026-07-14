package flow

import java.io.File

/** Renders a board + solution as ASCII. Same notation as docs/levels/. */
object Render {
    fun solution(b: Board, paths: Map<Char, List<Cell>>?): String {
        val w = 4 * b.cols
        val h = 2 * b.rows - 1
        val cv = Array(h) { CharArray(w) { ' ' } }
        val ep = HashMap<Cell, Char>()
        for ((c, pair) in b.endpoints) { ep[pair.first] = c; ep[pair.second] = c }

        for (r in 0 until b.rows) for (c in 0 until b.cols) {
            val cell = Cell(r, c)
            cv[2 * r][4 * c + 1] = when {
                cell in b.holes -> '#'
                cell in b.bridges -> '+'
                cell in ep -> ep[cell]!!
                else -> '.'
            }
        }

        paths?.forEach { (color, p) ->
            for (cell in p) {
                if (cell in b.bridges) continue
                if (cell !in ep) cv[2 * cell.row][4 * cell.col + 1] = color.lowercaseChar()
            }
            for (i in 0 until p.size - 1) {
                val a = p[i]; val z = p[i + 1]
                if (Math.abs(a.row - z.row) + Math.abs(a.col - z.col) != 1) continue  // seam: no glyph
                if (a.row == z.row) {
                    val cc = minOf(a.col, z.col)
                    for (j in 0..2) cv[2 * a.row][4 * cc + 2 + j] = '-'
                } else {
                    cv[2 * minOf(a.row, z.row) + 1][4 * a.col + 1] = '|'
                }
            }
        }

        for ((a, z) in b.walls) {
            val (p, q) = if (a.row < z.row || (a.row == z.row && a.col < z.col)) a to z else z to a
            if (p.row == q.row) cv[2 * p.row][4 * p.col + 3] = '#'
            else for (j in 0..2) cv[2 * p.row + 1][4 * p.col + j] = '='
        }

        return cv.joinToString("\n") { String(it).trimEnd() }
    }
}

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println(
            """
            flowsolve — Flow Free solver (all variants)

              flowsolve <puzzle.txt> [--quiet]

            Format (one parser, every variant):
              5 5
              B . . . R
              . . . . .
              . . Y . .
              . G . . .
              B R Y G .
              WALL 1,1 1,2       block the edge between two adjacent cells (both still exist)
              HOLE 2,3           cell is not on the board (excluded from coverage)
              BRIDGE 3,2         crossover: two flows pass straight through
              SEAM 0,2 2,6 R L   add an edge between non-adjacent cells (cube fold / warp)
            """.trimIndent()
        )
        kotlin.system.exitProcess(2)
    }

    val file = File(args[0])
    if (!file.exists()) {
        System.err.println("no such file: ${file.path}")
        kotlin.system.exitProcess(2)
    }
    val quiet = "--quiet" in args

    val board = try {
        Parser.parse(file.readText())
    } catch (e: Exception) {
        System.err.println("parse error: ${e.message}")
        kotlin.system.exitProcess(2)
    }

    if (!quiet) {
        println("PUZZLE  ${board.rows}x${board.cols}  ${board.nodes.size} nodes, " +
                "${board.colors.size} colours" +
                (if (board.walls.isNotEmpty()) ", ${board.walls.size} wall(s)" else "") +
                (if (board.holes.isNotEmpty()) ", ${board.holes.size} hole(s)" else "") +
                (if (board.bridges.isNotEmpty()) ", ${board.bridges.size} bridge(s)" else ""))
        println(Render.solution(board, null))
        println()
    }

    val sol = Flow.solve(board)          // SAT for real boards, DFS for bridges
    if (sol == null) {
        println("UNSOLVABLE" + if (!board.parityOk()) "  (rejected by parity pre-check — no search needed)" else "")
        kotlin.system.exitProcess(1)
    }

    Verifier.verify(board, sol.paths)   // never ship an answer we haven't re-checked

    println("SOLUTION  ${sol.nodesExplored} nodes explored, ${sol.elapsedMs} ms")
    println(Render.solution(board, sol.paths))
    println()
    for (c in board.colors) {
        println("$c: " + sol.paths[c]!!.joinToString(" -> ") { "${it.row},${it.col}" })
    }
}
