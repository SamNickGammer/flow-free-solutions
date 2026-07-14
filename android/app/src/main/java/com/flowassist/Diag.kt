package com.flowassist

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import flow.detect.Detector
import java.io.File
import java.io.FileOutputStream

/**
 * Diagnostics. The app must never fail silently again.
 *
 * Shipping this untested was a mistake; the fix is not "be more careful", it is "make the phone
 * tell us what happened". Everything lands in the app's external files dir, which needs no
 * permission and can be pulled over adb or shared by hand:
 *
 *   /sdcard/Android/data/com.flowassist/files/
 *       log.txt        every stage, in order
 *       shot.png       THE FRAME THE DETECTOR ACTUALLY SAW   <- the important one
 *       report.txt     what detection made of it, and why it gave up
 *
 * shot.png is worth more than any amount of my guessing: it is the real Flow Free screenshot this
 * project has never had.
 */
object Diag {

    private const val TAG = "FlowAssist"

    private fun dir(ctx: Context): File =
        (ctx.getExternalFilesDir(null) ?: ctx.filesDir).apply { mkdirs() }

    fun log(msg: String) {
        Log.i(TAG, msg)
        lastLines.add(msg)
        if (lastLines.size > 200) lastLines.removeAt(0)
    }

    private val lastLines = ArrayList<String>()

    fun crash(ctx: Context, stage: String, e: Throwable) {
        Log.e(TAG, "CRASH in $stage", e)
        lastLines.add("CRASH in $stage: ${e}")
        runCatching {
            File(dir(ctx), "log.txt").appendText(
                "\n=== CRASH in $stage ===\n" + Log.getStackTraceString(e) + "\n")
        }
    }

    /** The captured frame, exactly as the detector received it. */
    fun saveShot(ctx: Context, img: flow.detect.Image) {
        runCatching {
            val bmp = Bitmap.createBitmap(img.px, img.w, img.h, Bitmap.Config.ARGB_8888)
            FileOutputStream(File(dir(ctx), "shot.png")).use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bmp.recycle()
            log("saved shot.png (${img.w}x${img.h})")
        }.onFailure { Log.e(TAG, "could not save shot", it) }
    }

    /** What detection concluded — including, crucially, why it refused to produce a board. */
    fun report(ctx: Context, det: Detector.Result) {
        val sb = StringBuilder()
        sb.appendLine("=== detection report ===")
        val g = det.grid
        if (g == null) sb.appendLine("grid: NOT FOUND")
        else sb.appendLine("grid: ${g.rows}x${g.cols} at ${g.bounds} " +
                "cell ${"%.1f".format(g.cellW)}x${"%.1f".format(g.cellH)}px")

        val b = det.board
        if (b == null) sb.appendLine("board: NONE (refused to guess)")
        else sb.appendLine("board: ${b.colors.size} colours, ${b.walls.size} walls, " +
                "${b.holes.size} holes, ${b.nodes.size} nodes")

        if (det.problems.isEmpty()) sb.appendLine("problems: none")
        else det.problems.forEach { sb.appendLine("PROBLEM: ${it.what}") }

        det.endpointColors.forEach { (ch, c) ->
            sb.appendLine("  colour $ch = #%06X".format(c and 0xFFFFFF))
        }
        sb.appendLine()
        sb.appendLine("--- log ---")
        lastLines.forEach { sb.appendLine(it) }

        val txt = sb.toString()
        Log.i(TAG, txt)
        runCatching { File(dir(ctx), "report.txt").writeText(txt) }
    }

    fun path(ctx: Context): String = dir(ctx).absolutePath
}
