package flow.desktop

import flow.Parser
import flow.detect.BoardRender
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Writes a synthetic Flow-Free-looking screenshot to PNG so the CLI can be run on a real file. */
fun main(args: Array<String>) {
    val out = File(if (args.isNotEmpty()) args[0] else "shot.png")
    val puzzle = if (args.size > 1) File(args[1]).readText() else """
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
    """.trimIndent()
    val board = Parser.parse(puzzle)
    val (img, _) = BoardRender.render(board, cell = 56)
    val bi = BufferedImage(img.w, img.h, BufferedImage.TYPE_INT_RGB)
    bi.setRGB(0, 0, img.w, img.h, img.px, 0, img.w)
    ImageIO.write(bi, "png", out)
    println("wrote ${out.path}  ${img.w}x${img.h}  (${board.rows}x${board.cols}, ${board.colors.size} colours)")
}
