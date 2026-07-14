package flow.desktop

import flow.Flow
import flow.Render
import flow.Verifier
import flow.detect.Bounds
import flow.detect.Detector
import flow.detect.Image
import java.io.File
import javax.imageio.ImageIO

/**
 * Desktop CLI: screenshot in, solved board out.
 *
 *   flowdetect shot.png                       auto-detect
 *   flowdetect shot.png --bounds x0,y0,x1,y1  manual board bounds (calibration)
 *   flowdetect shot.png --size 14x14          force the grid size
 *
 * NOTE: ImageIO lives HERE and nowhere else. It does not exist on Android — on the phone the
 * bitmap comes from MediaProjection via Bitmap.getPixels() into the same IntArray. The detector
 * itself never learns which platform it is on.
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println(
            """
            flowdetect — read a Flow Free screenshot and solve it

              flowdetect <shot.png> [--bounds x0,y0,x1,y1] [--size RxC]

            If auto-detection misreads the board, pass --bounds (the calibration path the app
            gives the user by letting them drag the corners).
            """.trimIndent())
        kotlin.system.exitProcess(2)
    }

    val file = File(args[0])
    if (!file.exists()) { System.err.println("no such file: ${file.path}"); kotlin.system.exitProcess(2) }

    var bounds: Bounds? = null
    var size: Pair<Int, Int>? = null
    var i = 1
    while (i < args.size) {
        when (args[i]) {
            "--bounds" -> {
                val p = args[++i].split(",").map { it.trim().toInt() }
                require(p.size == 4) { "--bounds needs x0,y0,x1,y1" }
                bounds = Bounds(p[0], p[1], p[2], p[3])
            }
            "--size" -> {
                val p = args[++i].lowercase().split("x").map { it.trim().toInt() }
                require(p.size == 2) { "--size needs RxC, e.g. 14x14" }
                size = p[0] to p[1]
            }
            else -> { System.err.println("unknown flag ${args[i]}"); kotlin.system.exitProcess(2) }
        }
        i++
    }

    val bi = ImageIO.read(file) ?: run {
        System.err.println("could not decode ${file.path} as an image"); kotlin.system.exitProcess(2)
    }
    val px = IntArray(bi.width * bi.height)
    bi.getRGB(0, 0, bi.width, bi.height, px, 0, bi.width)
    val img = Image(bi.width, bi.height, px)

    println("image ${bi.width}x${bi.height}")

    val det = Detector.detect(img, bounds, size)

    det.grid?.let {
        println("board  ${it.bounds}  grid ${it.rows}x${it.cols}  " +
                "cell ${"%.1f".format(it.cellW)}x${"%.1f".format(it.cellH)} px")
    }

    if (det.problems.isNotEmpty()) {
        println("\nCOULD NOT READ THIS BOARD:")
        det.problems.forEach { println("  - ${it.what}") }
        println("\nTry --bounds x0,y0,x1,y1 to set the board rectangle by hand, and/or --size RxC.")
        println("(This is the calibration the app offers by letting you drag the corners.)")
        kotlin.system.exitProcess(1)
    }

    val board = det.board!!
    println("read   ${board.colors.size} colours" +
            (if (board.walls.isNotEmpty()) ", ${board.walls.size} wall(s)" else "") +
            (if (board.holes.isNotEmpty()) ", ${board.holes.size} hole(s)" else "") +
            "  (${board.nodes.size} nodes to cover)")
    println()
    println(Render.solution(board, null))

    val sol = Flow.solve(board, budgetMs = 10_000)
    if (sol == null) {
        println("\nUNSOLVABLE — which usually means the board was MISREAD, not that the level is bad.")
        println("Check walls vs holes: a wall keeps both cells (they must be filled); a hole removes one.")
        kotlin.system.exitProcess(1)
    }
    Verifier.verify(board, sol.paths)

    println("\nSOLVED in ${sol.elapsedMs} ms\n")
    println(Render.solution(board, sol.paths))

    println("\n-- screen coordinates for the overlay / auto-draw --")
    val g = det.grid!!
    for (c in board.colors) {
        val pts = sol.paths[c]!!.joinToString(" -> ") { cell ->
            val (x, y) = g.centerOf(cell.row, cell.col); "$x,$y"
        }
        println("$c: $pts")
    }
}
