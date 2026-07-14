package flow

import java.io.File

/**
 * Measurement harness. Never hangs — every solve carries a wall-clock budget.
 *
 *   ./gradlew bench --args="src/test/resources 10000"
 */
object Bench {
    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(if (args.isNotEmpty()) args[0] else "src/test/resources")
        val budget = if (args.size > 1) args[1].toLong() else 10_000L

        val files = dir.listFiles { f -> f.extension == "txt" }?.sortedBy { it.name } ?: emptyList()
        if (files.isEmpty()) { println("no .txt puzzles in $dir"); return }

        println("budget ${budget}ms per solve\n")
        println("%-26s %-9s %-4s %10s %8s  %s".format("puzzle", "size", "col", "nodes", "ms", "result"))
        println("-".repeat(74))

        for (f in files) {
            val board = try { Parser.parse(f.readText()) }
            catch (e: Exception) { println("%-26s PARSE ERROR: ${e.message}".format(f.name)); continue }

            val size = "${board.rows}x${board.cols}"
            val k = board.colors.size
            try {
                val s = Flow.solve(board, budgetMs = budget)
                if (s == null) {
                    println("%-26s %-9s %-4d %10s %8s  UNSOLVABLE".format(f.name, size, k, "-", "-"))
                } else {
                    Verifier.verify(board, s.paths)
                    val eng = if (SatEngine.supports(board)) "sat" else "dfs"
                    println("%-26s %-9s %-4d %10d %8d  ok   %s".format(
                        f.name, size, k, s.nodesExplored, s.elapsedMs, eng))
                }
            } catch (e: Solver.GaveUp) {
                println("%-26s %-9s %-4d %10d %8d  GAVE UP".format(
                    f.name, size, k, e.nodesExplored, e.elapsedMs))
            }
        }
    }
}
