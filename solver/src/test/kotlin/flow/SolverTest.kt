package flow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fixtures that earn their keep are the LOAD-BEARING ones: a board that only solves
 * BECAUSE of a wall, and one that becomes unsolvable when a transform is dropped.
 *
 * A solver that silently ignores walls still passes a test whose wall happened to sit off
 * the solution path. These don't let it.
 */
class SolverTest {

    private fun solve(text: String): Solution? {
        val b = Parser.parse(text)
        val s = Solver(b).solve()
        if (s != null) Verifier.verify(b, s.paths)   // every pass is independently re-checked
        return s
    }

    private fun solved(text: String): Solution =
        assertNotNull(solve(text), "expected solvable, got UNSOLVABLE")

    // ---------------------------------------------------------------- plain

    @Test fun `plain 5x5 solves with full coverage`() {
        val s = solved("""
            5 5
            D . . . .
            . . D C .
            A . . . .
            A . . . .
            B B C . .
        """.trimIndent())
        val covered = s.paths.values.flatten().toSet()
        assertEquals(25, covered.size, "every cell must be filled")
    }

    @Test fun `tiny 3x3`() {
        // NB: the "A . B / . . . / A . B" board people reach for first is PROVABLY UNSOLVABLE
        // (parity: 3x3 needs S-T = +1, those endpoints give +2). See `parity rejects` below.
        val s = solved("""
            3 3
            A A B
            . B .
            . . .
        """.trimIndent())
        assertEquals(9, s.paths.values.flatten().toSet().size)
    }

    @Test fun `the classic-looking 3x3 is provably unsolvable`() {
        val b = Parser.parse("3 3\nA . B\n. . .\nA . B")
        assertTrue(!b.parityOk(), "parity must reject it without searching")
        assertNull(Solver(b).solve())
    }

    // ---------------------------------------------------------------- WALLS

    @Test fun `wall forces A to detour instead of going straight across`() {
        // A's endpoints are (2,0) and (2,2). The wall between (2,1)-(2,2) blocks the
        // direct route, so A MUST go around through the row above.
        val puzzle = """
            5 5
            . . . . B
            B . . . C
            A . A . .
            . D . . .
            D C . . .
            WALL 2,1 2,2
        """.trimIndent()
        val s = solved(puzzle)
        val a = s.paths['A']!!
        assertTrue(a.size > 3, "A took the straight route — the wall was ignored! path=$a")
        // and the step the wall forbids must not appear
        assertTrue(a.zipWithNext().none { (x, y) ->
            setOf(x, y) == setOf(Cell(2, 1), Cell(2, 2))
        }, "A stepped straight through the wall")
    }

    @Test fun `walling a cell off on all 4 sides makes the board UNSOLVABLE`() {
        // THE bug this whole design guards against: an obstacle modelled as walls.
        // The cell still exists, so coverage demands it be filled — but nothing can reach it.
        assertNull(solve("""
            5 5
            B A . . .
            . . . . .
            . . . A .
            . B C D .
            . . C D .
            WALL 2,2 1,2
            WALL 2,2 3,2
            WALL 2,2 2,1
            WALL 2,2 2,3
        """.trimIndent()), "a fenced-off cell must still be covered — board cannot be solvable")
    }

    // ---------------------------------------------------------------- HOLES

    @Test fun `hole is excluded from coverage — same board is solvable`() {
        // Identical to the test above, except (2,2) is REMOVED rather than fenced.
        val s = solved("""
            5 5
            B A . . .
            . . . . .
            . . # A .
            . B C D .
            . . C D .
        """.trimIndent())
        val covered = s.paths.values.flatten().toSet()
        assertEquals(24, covered.size, "24 nodes, not 25 — the hole must NOT be filled")
        assertTrue(Cell(2, 2) !in covered, "the hole was filled")
    }

    @Test fun `courtyard — holes stay empty`() {
        val s = solved("""
            6 6
            . . C B . B
            D D C . . A
            E . # # . .
            . . # # . .
            . E . . A .
            . . . . . .
        """.trimIndent())
        assertEquals(32, s.paths.values.flatten().toSet().size, "36 - 4 holes = 32")
    }

    // ---------------------------------------------------------------- BRIDGES

    @Test fun `a bridge is crossed once on each axis, always straight through`() {
        // '+' in the grid marks a bridge (BRIDGE 2,2 directive also works).
        val s = solved("""
            5 5
            . . . . .
            . C . . .
            A . + . A
            D D . C .
            B B . . .
        """.trimIndent())
        val br = Cell(2, 2)

        var horiz = 0
        var vert = 0
        for ((c, p) in s.paths) {
            for (i in p.indices) {
                if (p[i] != br) continue
                assertTrue(i > 0 && i < p.size - 1, "$c starts/ends on the bridge")
                val prev = p[i - 1]; val next = p[i + 1]
                when {
                    prev.row == br.row && next.row == br.row -> horiz++
                    prev.col == br.col && next.col == br.col -> vert++
                    else -> throw AssertionError("$c turned on the bridge — must go straight through")
                }
            }
        }
        assertEquals(1, horiz, "the bridge must carry exactly one horizontal pass")
        assertEquals(1, vert, "the bridge must carry exactly one vertical pass")

        // NOTE: we deliberately do NOT assert the two passes are different colours.
        // Whether a flow may cross ITSELF on a bridge is unverified against the real game, and
        // the solver currently permits it (permissive = it will never wrongly reject a solvable
        // board). If a real Bridges level proves self-crossing is illegal, add the constraint in
        // Solver.walk(). See docs/levels/05-bridges.md.
    }

    // ---------------------------------------------------------------- SEAMS (cubes)

    @Test fun `seam edge is load-bearing — board dies without it`() {
        // Two 3x3 faces (cols 0-2 and 4-6); col 3 is the fold, not a cell.
        // Seam joins face A's right column to face B's right column, REVERSED:
        //   A(r,2) <-> B(2-r,6).  Both faces exit rightward into the fold -> "R R".
        val withSeam = """
            3 7
            . . . # D C .
            . A . # . . C
            A B . # D . B
            SEAM 0,2 2,6 R R
            SEAM 1,2 1,6 R R
            SEAM 2,2 0,6 R R
        """.trimIndent()
        val s = solved(withSeam)

        // a step across the seam is a NON-ADJACENT jump in the flat net
        val jumped = s.paths.values.any { p ->
            p.zipWithNext().any { (x, y) ->
                Math.abs(x.row - y.row) + Math.abs(x.col - y.col) != 1
            }
        }
        assertTrue(jumped, "no seam was used — the seam edges did nothing")

        // drop the seams: the two faces are disconnected, so it MUST become unsolvable.
        // This is what proves the seams are load-bearing and not decorative.
        val noSeam = withSeam.lines().filterNot { it.trimStart().startsWith("SEAM") }
            .joinToString("\n")
        assertNull(solve(noSeam), "without seams the faces are disconnected — must be unsolvable")
    }

    // ---------------------------------------------------------------- RECTANGLE

    @Test fun `non-square board — R != C`() {
        val s = solved("""
            4 7
            . A D . . D E
            A B C . . E .
            B . . . . . .
            C . . . . . .
        """.trimIndent())
        assertEquals(28, s.paths.values.flatten().toSet().size)
    }

    // ---------------------------------------------------------------- PARITY

    @Test fun `parity pre-check rejects an impossible board without searching`() {
        // 5x5 has 13 even + 12 odd cells, so any solution needs S-T = +1.
        // Give every colour endpoints of opposite parity => S-T = 0 => impossible.
        val b = Parser.parse("""
            5 5
            A B . . .
            A B . . .
            . . . . .
            C D . . .
            C D . . .
        """.trimIndent())
        assertTrue(!b.parityOk(), "this board violates the parity invariant")
        assertNull(Solver(b).solve())
    }

    @Test fun `parity holds on every solvable board we know`() {
        // walled board keeps the node -> needs S-T = +1
        // holed board drops it       -> needs S-T = 0
        for (p in listOf(
            "5 5\nD . . . .\n. . D C .\nA . . . .\nA . . . .\nB B C . .",
            "5 5\nB A . . .\n. . . . .\n. . # A .\n. B C D .\n. . C D .",
        )) {
            val b = Parser.parse(p)
            assertTrue(b.parityOk(), "solvable board must satisfy parity")
            assertNotNull(Solver(b).solve())
        }
    }

    // ---------------------------------------------------------------- PARSER

    @Test fun `parser rejects a colour with only one dot`() {
        val e = assertFailsWith<IllegalArgumentException> {
            Parser.parse("3 3\nA . .\n. . .\n. . B")
        }
        assertTrue(e.message!!.contains("exactly 2 endpoints"), e.message!!)
    }

    @Test fun `parser rejects a non-adjacent WALL`() {
        val e = assertFailsWith<IllegalArgumentException> {
            Parser.parse("3 3\nA . .\n. . .\nA . .\nWALL 0,0 2,2")
        }
        assertTrue(e.message!!.contains("adjacent"), e.message!!)
    }

    @Test fun `parser rejects a ragged grid`() {
        assertFailsWith<IllegalArgumentException> {
            Parser.parse("3 3\nA . .\n. .\nA . .")
        }
    }
}
