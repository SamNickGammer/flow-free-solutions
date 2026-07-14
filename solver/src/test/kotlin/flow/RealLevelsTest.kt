package flow

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The test that decides whether this project is real: 29 ACTUAL Flow Free levels
 * (regular / extreme / jumbo, 5x5 up to 14x14), every one solved and independently verified.
 *
 * These are not puzzles I invented. Hand-made fixtures let a solver look good on boards that
 * happen to suit it — that is exactly how the DFS engine fooled me for an afternoon.
 *
 * Budget: a one-tap phone assistant cannot make the user stare at a spinner. 3s on a desktop JVM
 * is the ceiling here; a phone is a few times slower, so anything close to it is a warning.
 */
class RealLevelsTest {

    private val budgetMs = 3_000L

    private fun levels(): List<File> {
        val dir = File(javaClass.getResource("/real")?.toURI() ?: error("missing /real fixtures"))
        return dir.listFiles { f -> f.extension == "txt" }!!.sortedBy { it.name }
    }

    @Test fun `every real Flow Free level solves, verified, inside the budget`() {
        var worstMs = 0L
        var worstName = ""
        var solved = 0
        var unsolvable = 0

        for (f in levels()) {
            val board = Parser.parse(f.readText())
            val s = Flow.solve(board, budgetMs = budgetMs)

            if (f.name.startsWith("unsolvable")) {
                assertTrue(s == null, "${f.name} is meant to be unsolvable but we 'solved' it")
                unsolvable++
                continue
            }

            assertNotNull(s, "${f.name} did not solve")
            Verifier.verify(board, s.paths)      // paths join endpoints, legal edges, full coverage

            // every cell filled — the rule that makes Flow Free hard
            val covered = s.paths.values.flatten().toSet()
            assertTrue(covered.size == board.nodes.size,
                "${f.name}: covered ${covered.size} of ${board.nodes.size} cells")

            if (s.elapsedMs > worstMs) { worstMs = s.elapsedMs; worstName = f.name }
            solved++
        }

        println("solved $solved real levels (+$unsolvable correctly rejected); " +
                "worst = $worstName at ${worstMs}ms")
        assertTrue(solved >= 25, "expected the full corpus, only solved $solved")
        assertTrue(worstMs < budgetMs, "TOO SLOW: $worstName took ${worstMs}ms")
    }

    @Test fun `bridges still route through the DFS engine`() {
        // SAT's "every cell has exactly one colour" cannot express a cell carrying two flows,
        // so bridges MUST fall back to the search engine. Guard that routing.
        val board = Parser.parse("""
            5 5
            . . . . .
            . C . . .
            A . + . A
            D D . C .
            B B . . .
        """.trimIndent())
        assertTrue(!SatEngine.supports(board), "a bridge board must not be handed to SAT")
        val s = assertNotNull(Flow.solve(board), "bridge board did not solve")
        Verifier.verify(board, s.paths)
    }
}
