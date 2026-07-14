package flow.detect

import flow.Board
import flow.Cell
import flow.Flow
import flow.Parser
import flow.Verifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Round-trip: puzzle -> rendered screenshot -> detector -> puzzle. Compare against ground truth.
 *
 * The rendered image is not a real screenshot (see BoardRender's header), so passing here proves
 * the pipeline is SOUND, not that it survives a phone. The load-bearing cases are the ones that
 * separate things which look alike on screen:
 *   - a WALL from an ordinary grid line
 *   - a WALL from a HOLE   <- get this wrong and every board reports unsolvable
 *   - rows from cols       <- assume square and every Rectangle level mis-parses
 */
class DetectorTest {

    private fun roundTrip(puzzle: String): Pair<Board, Detector.Result> {
        val truth = Parser.parse(puzzle)
        val (img, _) = BoardRender.render(truth)
        val got = Detector.detect(img)
        return truth to got
    }

    private fun sameShape(truth: Board, got: Board) {
        assertEquals(truth.rows, got.rows, "rows")
        assertEquals(truth.cols, got.cols, "cols")
        assertEquals(truth.holes, got.holes, "holes")
        assertEquals(truth.colors.size, got.colors.size, "number of colours")

        // endpoint pairs must match as a SET of cell-pairs (letters are assigned by the detector
        // in scan order, so they need not agree with the source file)
        fun pairsOf(b: Board) = b.endpoints.values.map { setOf(it.first, it.second) }.toSet()
        assertEquals(pairsOf(truth), pairsOf(got), "endpoint pairs")

        fun wallsOf(b: Board) = b.walls.map { setOf(it.first, it.second) }.toSet()
        assertEquals(wallsOf(truth), wallsOf(got), "walls")
    }

    @Test fun `plain 5x5 round-trips`() {
        val (truth, got) = roundTrip("""
            5 5
            D . . . .
            . . D C .
            A . . . .
            A . . . .
            B B C . .
        """.trimIndent())
        assertTrue(got.problems.isEmpty(), "problems: ${got.problems}")
        sameShape(truth, assertNotNull(got.board))
    }

    @Test fun `a WALL is read as a wall, not a grid line and not a hole`() {
        val (truth, got) = roundTrip("""
            5 5
            . . . . B
            B . . . C
            A . A . .
            . D . . .
            D C . . .
            WALL 2,1 2,2
        """.trimIndent())
        assertTrue(got.problems.isEmpty(), "problems: ${got.problems}")
        val b = assertNotNull(got.board)

        assertEquals(1, b.walls.size, "expected exactly 1 wall, got ${b.walls}")
        assertEquals(setOf(Cell(2, 1), Cell(2, 2)), b.walls.first().let { setOf(it.first, it.second) })
        assertTrue(b.holes.isEmpty(), "a wall must NOT be read as a hole — got holes ${b.holes}")
        assertEquals(25, b.nodes.size, "a walled cell still exists and still must be covered")
        sameShape(truth, b)
    }

    @Test fun `a HOLE is read as a hole, not a wall`() {
        val (truth, got) = roundTrip("""
            5 5
            B A . . .
            . . . . .
            . . # A .
            . B C D .
            . . C D .
        """.trimIndent())
        assertTrue(got.problems.isEmpty(), "problems: ${got.problems}")
        val b = assertNotNull(got.board)

        assertEquals(setOf(Cell(2, 2)), b.holes)
        assertTrue(b.walls.isEmpty(), "a hole must NOT be read as a wall — got ${b.walls}")
        assertEquals(24, b.nodes.size, "the hole is NOT a node and must not be covered")
        sameShape(truth, b)
    }

    @Test fun `rows and cols are measured independently`() {
        // A detector that finds 4 rows and assumes 4x4 mis-parses every Rectangle level.
        val (truth, got) = roundTrip("""
            4 7
            . A D . . D E
            A B C . . E .
            B . . . . . .
            C . . . . . .
        """.trimIndent())
        assertTrue(got.problems.isEmpty(), "problems: ${got.problems}")
        val b = assertNotNull(got.board)
        assertEquals(4, b.rows); assertEquals(7, b.cols)
        sameShape(truth, b)
    }

    @Test fun `courtyard of holes`() {
        val (truth, got) = roundTrip("""
            6 6
            . . C B . B
            D D C . . A
            E . # # . .
            . . # # . .
            . E . . A .
            . . . . . .
        """.trimIndent())
        assertTrue(got.problems.isEmpty(), "problems: ${got.problems}")
        val b = assertNotNull(got.board)
        assertEquals(4, b.holes.size)
        assertEquals(32, b.nodes.size)
        sameShape(truth, b)
    }

    @Test fun `end to end — screenshot in, solved and verified out`() {
        val truth = Parser.parse("""
            9 9
            . . . . . . . . .
            . . . . . . . . .
            . . F . B B C . .
            . . G . A . . . .
            . . . . . . A . C
            . . . . . . . . D
            . . . . . . . . D
            . . . . . F . . E
            G . . . . E . . .
        """.trimIndent())
        val (img, grid) = BoardRender.render(truth)

        val det = Detector.detect(img)
        assertTrue(det.problems.isEmpty(), "problems: ${det.problems}")
        val board = assertNotNull(det.board, "detection produced no board")

        val sol = assertNotNull(Flow.solve(board), "detected board did not solve")
        Verifier.verify(board, sol.paths)
        assertEquals(board.nodes.size, sol.paths.values.flatten().toSet().size, "full coverage")

        // the geometry the app needs to actually draw on screen
        val g = assertNotNull(det.grid)
        assertEquals(grid.rows, g.rows); assertEquals(grid.cols, g.cols)
        val (cx, cy) = g.centerOf(0, 0)
        val (tx, ty) = grid.centerOf(0, 0)
        assertTrue(Math.abs(cx - tx) <= 3 && Math.abs(cy - ty) <= 3,
            "cell centre off by ($cx,$cy) vs ($tx,$ty) — auto-draw would swipe the wrong cell")
    }

    @Test fun `a colour with three dots is reported, not silently guessed`() {
        // The detector's strongest self-check: every colour must have EXACTLY two dots.
        val truth = Parser.parse("3 3\nA . A\n. . .\nB . B")
        val (img, _) = BoardRender.render(truth)
        // paint a third 'A'-coloured dot into the middle of the board
        val colors = BoardRender.defaultColors(truth)
        val a = colors['A']!!
        val g = Detector.Grid(Bounds(33, 33, 33 + 3 * 48, 33 + 3 * 48), 3, 3, 48.0, 48.0)
        val (cx, cy) = g.centerOf(1, 1)
        for (y in cy - 12..cy + 12) for (x in cx - 12..cx + 12) {
            if (img.inside(x, y)) img.px[y * img.w + x] = a
        }

        val got = Detector.detect(img)
        assertTrue(got.problems.isNotEmpty(),
            "an unpairable dot must be REPORTED, not guessed. problems=${got.problems}")
        assertTrue(got.board == null, "must not hand a bad board to the solver")
    }
}
