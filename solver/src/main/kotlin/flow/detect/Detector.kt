package flow.detect

import flow.Board
import flow.Cell
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Screenshot -> puzzle model.
 *
 * This is the riskiest component in the whole project. The solver is now proven; the detector is
 * the thing that will actually be wrong on a real phone — themes, DPI, notches, ad banners.
 *
 * So it is built to FAIL LOUDLY, not to guess:
 *   - every stage reports what it found, and a confidence
 *   - anything ambiguous becomes a [Problem], not a silent assumption
 *   - the caller can override the board bounds and the grid size by hand (the calibration path
 *     the UX promises), and every stage downstream still works
 *
 * A confidently wrong board is far worse than an honest "I couldn't read this" — and with
 * auto-draw armed it is actively destructive: it will swipe the wrong cells and wreck a
 * half-finished level.
 */
object Detector {

    /**
     * How close a pixel must be to the board background to count as board, squared RGB distance.
     * The board and the page behind it are both dark grey and only ~40 units apart, so this has to
     * stay tight. Real screenshots (themes, gradients) will want it tuned — it is a knob, and the
     * manual bounds override exists for when tuning is not enough.
     */
    var BG_TOLERANCE: Int = 18 * 18 * 3

    data class Problem(val what: String)

    data class Grid(
        val bounds: Bounds,
        val rows: Int,
        val cols: Int,
        val cellW: Double,
        val cellH: Double,
    ) {
        /** Centre of cell (r,c) in SCREEN pixels — also what auto-draw needs to aim a swipe. */
        fun centerOf(r: Int, c: Int): Pair<Int, Int> = Pair(
            (bounds.x0 + (c + 0.5) * cellW).roundToInt(),
            (bounds.y0 + (r + 0.5) * cellH).roundToInt(),
        )
    }

    data class Result(
        val grid: Grid?,
        val board: Board?,
        val problems: List<Problem>,
        val endpointColors: Map<Char, Int>,   // colour letter -> the RGB actually seen on screen
    ) {
        val ok: Boolean get() = board != null && problems.isEmpty()
    }

    /**
     * @param boundsHint skip board-finding and use these bounds (manual calibration)
     * @param sizeHint   skip grid-sizing and use this rows x cols
     */
    fun detect(img: Image, boundsHint: Bounds? = null, sizeHint: Pair<Int, Int>? = null): Result {
        val problems = ArrayList<Problem>()

        val bounds = boundsHint ?: findBoard(img)
        if (bounds == null) {
            return Result(null, null, listOf(Problem("could not find the board in this image")), emptyMap())
        }
        if (bounds.width < 40 || bounds.height < 40) {
            return Result(null, null, listOf(Problem("board region is implausibly small: $bounds")), emptyMap())
        }

        val bg = img.dominantColor(bounds)

        val grid = sizeHint?.let { (r, c) ->
            Grid(bounds, r, c, bounds.width.toDouble() / c, bounds.height.toDouble() / r)
        } ?: findGrid(img, bounds, bg)

        if (grid == null) {
            return Result(null, null,
                listOf(Problem("found a board at $bounds but could not read its grid lines")), emptyMap())
        }
        if (grid.rows !in 2..20 || grid.cols !in 2..20) {
            problems.add(Problem("implausible grid size ${grid.rows}x${grid.cols}"))
        }

        // ---- classify every cell
        val holes = HashSet<Cell>()
        val bridges = HashSet<Cell>()
        val dots = ArrayList<Pair<Cell, Int>>()      // cell -> observed RGB

        val sampleR = (minOf(grid.cellW, grid.cellH) * 0.18).toInt().coerceAtLeast(1)

        for (r in 0 until grid.rows) {
            for (c in 0 until grid.cols) {
                val (cx, cy) = grid.centerOf(r, c)
                val col = img.patchMedian(cx, cy, sampleR)
                when (classify(col, bg)) {
                    CellKind.EMPTY -> {}
                    CellKind.HOLE -> holes.add(Cell(r, c))
                    CellKind.DOT -> dots.add(Cell(r, c) to col)
                }
            }
        }

        // ---- cluster the dots into colour pairs
        val (pairs, colorProblems, seen) = pairUp(dots)
        problems.addAll(colorProblems)

        // ---- walls: a thick bright line ON the border between two cells
        val walls = findWalls(img, grid, bg, holes)

        if (pairs.isEmpty()) problems.add(Problem("no endpoint pairs found"))

        // NEVER hand a doubtful read to the solver. A wrong board does not fail loudly — it either
        // reports "unsolvable" (and you blame the solver) or, with auto-draw armed, it swipes the
        // wrong cells and wrecks a half-finished level. If anything is off, return no board.
        if (problems.isNotEmpty()) return Result(grid, null, problems, seen)

        val board = try {
            Board(grid.rows, grid.cols, holes, walls, bridges, emptyList(), pairs)
        } catch (e: Exception) {
            problems.add(Problem("built an invalid board: ${e.message}"))
            return Result(grid, null, problems, seen)
        }

        return Result(grid, board, problems, seen)
    }

    // ---------------------------------------------------------------- board bounds

    /**
     * The board is a large, flat, near-uniform field. Find the dominant colour of the middle of the
     * screen, then take the bounding box of the biggest connected blob of it.
     *
     * Deliberately simple. This is the stage most likely to be defeated by a real screenshot (ad
     * banners, gradient backgrounds, themes), which is exactly why `boundsHint` exists — the UI
     * lets the user drag the corners and we never touch this code path again for that device.
     */
    fun findBoard(img: Image): Bounds? {
        val mid = Bounds(img.w / 4, img.h / 4, img.w * 3 / 4, img.h * 3 / 4)
        val bg = img.dominantColor(mid)

        // TOLERANCE IS A CALIBRATION KNOB, and it starts tight on purpose.
        // The board background and the page background behind it are both dark grey and are only
        // ~40 RGB units apart. A loose tolerance swallows the page and reports the whole screen as
        // the board — which is exactly what the first version did.
        val tol = BG_TOLERANCE

        // column/row occupancy of "looks like board background"
        val colHits = IntArray(img.w)
        val rowHits = IntArray(img.h)
        var total = 0
        for (y in 0 until img.h step 2) {
            for (x in 0 until img.w step 2) {
                if (dist2(img[x, y], bg) <= tol) { colHits[x]++; rowHits[y]++; total++ }
            }
        }
        if (total < 100) return null

        // a board row/column is one where the background runs across a big fraction of the board
        val colThresh = (colHits.max() * 0.5).toInt().coerceAtLeast(1)
        val rowThresh = (rowHits.max() * 0.5).toInt().coerceAtLeast(1)

        val x0 = (0 until img.w).firstOrNull { colHits[it] >= colThresh } ?: return null
        val x1 = (img.w - 1 downTo 0).firstOrNull { colHits[it] >= colThresh } ?: return null
        val y0 = (0 until img.h).firstOrNull { rowHits[it] >= rowThresh } ?: return null
        val y1 = (img.h - 1 downTo 0).firstOrNull { rowHits[it] >= rowThresh } ?: return null

        if (x1 <= x0 || y1 <= y0) return null
        return Bounds(x0, y0, x1 + 1, y1 + 1)
    }

    // ---------------------------------------------------------------- grid size

    /**
     * Grid lines are thin runs of pixels that differ from the cell background. Project them onto
     * each axis, find the peaks, and take the MEDIAN GAP as the cell size.
     *
     * Rows and columns are measured INDEPENDENTLY. A detector that finds 9 rows and assumes 9x9
     * silently mis-parses every Rectangle level — and it looks like a solver bug, not a detector
     * one. See docs/levels/02-rectangle.md.
     */
    fun findGrid(img: Image, b: Bounds, bg: Int): Grid? {
        val colScore = DoubleArray(b.width)
        val rowScore = DoubleArray(b.height)

        for (i in 0 until b.width) {
            var s = 0.0
            for (y in b.y0 until b.y1) s += if (dist2(img[b.x0 + i, y], bg) > 200) 1.0 else 0.0
            colScore[i] = s / b.height
        }
        for (j in 0 until b.height) {
            var s = 0.0
            for (x in b.x0 until b.x1) s += if (dist2(img[x, b.y0 + j], bg) > 200) 1.0 else 0.0
            rowScore[j] = s / b.width
        }

        val cols = spacingOf(colScore) ?: return null
        val rows = spacingOf(rowScore) ?: return null

        val nCols = (b.width / cols).roundToInt()
        val nRows = (b.height / rows).roundToInt()
        if (nCols < 2 || nRows < 2) return null

        return Grid(b, nRows, nCols, b.width.toDouble() / nCols, b.height.toDouble() / nRows)
    }

    /** Median gap between grid-line peaks in a projection profile. */
    private fun spacingOf(score: DoubleArray): Double? {
        // a grid line runs the full length of the board, so it scores high across the profile
        val thresh = 0.6
        val peaks = ArrayList<Int>()
        var i = 0
        while (i < score.size) {
            if (score[i] >= thresh) {
                val start = i
                while (i < score.size && score[i] >= thresh) i++
                peaks.add((start + i - 1) / 2)          // centre of the line
            } else i++
        }
        if (peaks.size < 2) return null
        val gaps = (1 until peaks.size).map { (peaks[it] - peaks[it - 1]).toDouble() }
            .filter { it > 3 }                          // ignore a doubled-up line
            .sorted()
        if (gaps.isEmpty()) return null
        return gaps[gaps.size / 2]
    }

    // ---------------------------------------------------------------- cells

    private enum class CellKind { EMPTY, DOT, HOLE }

    /**
     * Three-way. The key signal is SATURATION: Flow Free's dots are vivid, and the board, grid
     * lines and empty cells are all grey. A hole is darker than the board and still grey.
     */
    private fun classify(c: Int, bg: Int): CellKind {
        if (saturation(c) > 60) return CellKind.DOT
        if (dist2(c, bg) < 900) return CellKind.EMPTY
        // grey but clearly not the board background: a hole is DARKER, a light-grey dot is not.
        return if (luma(c) < luma(bg) - 18) CellKind.HOLE else CellKind.EMPTY
    }

    /**
     * Pair the dots up by colour.
     *
     * This is a MATCHING problem, not a clustering problem, and the difference matters. Threshold
     * clustering ("group everything within tolerance X") needs a tolerance that is simultaneously
     * loose enough to survive antialiasing and tight enough to separate Flow Free's yellow from its
     * orange — which are only ~5700 apart in squared RGB. The first version merged them into one
     * four-dot "colour".
     *
     * But we know something far stronger: **every colour has EXACTLY TWO dots.** So: sort all dot
     * pairings by colour distance and greedily take the closest available. Two dots of the same
     * colour are near-identical pixels, so true pairs are matched long before any cross-colour pair
     * is considered. No tolerance to tune.
     *
     * Every dot ending up in exactly one pair is then the detector's strongest self-check. If it
     * fails, the read is WRONG, and we say so instead of handing a bad board to the solver.
     */
    private fun pairUp(dots: List<Pair<Cell, Int>>):
        Triple<Map<Char, Pair<Cell, Cell>>, List<Problem>, Map<Char, Int>> {

        val problems = ArrayList<Problem>()
        if (dots.isEmpty()) return Triple(emptyMap(), problems, emptyMap())

        if (dots.size % 2 != 0) {
            problems.add(Problem(
                "found ${dots.size} dots — an odd number, so they cannot pair up. " +
                "Dots at ${dots.map { it.first }}"))
        }

        data class Cand(val i: Int, val j: Int, val d: Int)
        val cands = ArrayList<Cand>()
        for (i in dots.indices) for (j in i + 1 until dots.size) {
            cands.add(Cand(i, j, dist2(dots[i].second, dots[j].second)))
        }
        cands.sortBy { it.d }

        val partner = IntArray(dots.size) { -1 }
        for (c in cands) {
            if (partner[c.i] < 0 && partner[c.j] < 0) { partner[c.i] = c.j; partner[c.j] = c.i }
        }

        // A true pair is the SAME colour rendered twice — near-identical. Anything else is the
        // greedy matcher scraping the barrel, and means the read is bad.
        val sameColor = 40 * 40 * 3

        val pairs = HashMap<Char, Pair<Cell, Cell>>()
        val seen = HashMap<Char, Int>()
        var letter = 'A'
        val used = BooleanArray(dots.size)

        for (i in dots.indices) {
            if (used[i]) continue
            val j = partner[i]
            if (j < 0) {
                problems.add(Problem("dot at ${dots[i].first} has no matching second dot"))
                used[i] = true
                continue
            }
            used[i] = true; used[j] = true
            val d = dist2(dots[i].second, dots[j].second)
            if (d > sameColor) {
                problems.add(Problem(
                    "dots at ${dots[i].first} and ${dots[j].first} were paired but their colours " +
                    "differ too much (d2=$d) — the grid was probably misread"))
                continue
            }
            pairs[letter] = dots[i].first to dots[j].first
            seen[letter] = dots[i].second
            letter++
        }
        return Triple(pairs, problems, seen)
    }

    // ---------------------------------------------------------------- walls

    /**
     * A wall is a THICK BRIGHT line drawn ON the border between two cells. An ordinary grid line
     * sits on the same border and is thin and faint. So we discriminate on BRIGHTNESS, not on
     * presence — "is there a line here" is true for every border on the board.
     *
     * Remember what is at stake: a wall keeps BOTH its cells (they must still be filled), a hole
     * removes one (it must not). Read a wall as a hole and the coverage count shifts by one and
     * every board reports unsolvable. See docs/levels/03-walls.md.
     */
    fun findWalls(img: Image, g: Grid, bg: Int, holes: Set<Cell>): Set<Pair<Cell, Cell>> {
        val walls = HashSet<Pair<Cell, Cell>>()
        val bgLuma = luma(bg)
        // a wall is markedly brighter than the board; a grid line is only slightly off it
        val wallLuma = bgLuma + 55

        fun borderIsWall(ax: Int, ay: Int, bx: Int, by: Int): Boolean {
            // sample along the shared border, between the two cell centres
            var bright = 0
            var n = 0
            val steps = 9
            for (s in 1 until steps) {
                val t = s.toDouble() / steps
                // midpoint of the border, swept along its length
                val mx = (ax + bx) / 2.0
                val my = (ay + by) / 2.0
                // sweep perpendicular to the cell-to-cell direction
                val perpX = -(by - ay).toDouble()
                val perpY = (bx - ax).toDouble()
                val len = Math.hypot(perpX, perpY).coerceAtLeast(1.0)
                val ux = perpX / len; val uy = perpY / len
                val half = (if (ax == bx) g.cellW else g.cellH) * 0.30
                val off = (t - 0.5) * 2 * half
                val x = (mx + ux * off).roundToInt()
                val y = (my + uy * off).roundToInt()
                if (!img.inside(x, y)) continue
                if (luma(img.patchMedian(x, y, 1)) >= wallLuma) bright++
                n++
            }
            return n > 0 && bright >= (n * 0.6)
        }

        for (r in 0 until g.rows) {
            for (c in 0 until g.cols) {
                val cell = Cell(r, c)
                if (cell in holes) continue
                val (cx, cy) = g.centerOf(r, c)
                if (c + 1 < g.cols && Cell(r, c + 1) !in holes) {
                    val (nx, ny) = g.centerOf(r, c + 1)
                    if (borderIsWall(cx, cy, nx, ny)) walls.add(cell to Cell(r, c + 1))
                }
                if (r + 1 < g.rows && Cell(r + 1, c) !in holes) {
                    val (nx, ny) = g.centerOf(r + 1, c)
                    if (borderIsWall(cx, cy, nx, ny)) walls.add(cell to Cell(r + 1, c))
                }
            }
        }
        return walls
    }
}
