package com.flowassist

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.*
import android.widget.Toast
import flow.Flow
import flow.detect.Bounds
import flow.detect.Detector
import kotlin.math.abs

/**
 * The whole product: a bubble over the game.
 *
 * Tap it -> capture one frame -> read the board -> solve -> show a FAINT overlay with direction
 * arrows that you trace with your own finger on the real game underneath.
 *
 * The solve is silent: nothing is drawn on the board until it has an answer, and it never draws
 * anything if it could not read the board confidently.
 */
class AssistService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        private const val CHANNEL = "flowassist"
        private const val NOTIF_ID = 1
    }

    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var bubble: View? = null
    private var overlay: OverlayView? = null

    private val main = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("solve").apply { start() }
    private val bg = Handler(worker.looper)

    private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ANDROID 14: a mediaProjection foreground service must declare its type HERE, not just in
        // the manifest. The 2-arg startForeground() throws and the service dies the instant it
        // starts — which looks exactly like "it crashed" or "nothing happened".
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIF_ID, notification())
            }
        } catch (e: Throwable) {
            Diag.crash(this, "startForeground", e)
            stopSelf()
            return START_NOT_STICKY
        }

        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (code != 0 && data != null && projection == null) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(code, data).apply {
                registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { projection = null }
                }, main)
            }
        }

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (bubble == null) addBubble()
        return START_STICKY
    }

    // ------------------------------------------------------------------ bubble

    private fun addBubble() {
        val v = BubbleView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 400
        }

        // drag to move, tap to solve
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragged = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (abs(dx) > 12 || abs(dy) > 12) dragged = true
                    lp.x = startX + dx; lp.y = startY + dy
                    wm.updateViewLayout(v, lp)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) onBubbleTapped()
                    true
                }
                else -> false
            }
        }
        wm.addView(v, lp)
        bubble = v
    }

    private fun onBubbleTapped() {
        Diag.log("bubble tapped (busy=$busy, overlay=${overlay != null})")
        if (busy) return
        // Second tap while an answer is showing = dismiss it
        if (overlay != null) { clearOverlay(); return }

        val p = projection
        if (p == null) {
            toast("Screen capture permission was lost — reopen Flow Assist")
            return
        }
        busy = true
        (bubble as BubbleView).setBusy(true)

        // HIDE OUR OWN UI BEFORE CAPTURING.
        // Otherwise the bubble lands in the screenshot and the detector tries to read it as part
        // of the board. It is exactly the kind of bug that only shows up on a real device.
        bubble?.visibility = View.GONE

        main.postDelayed({ capture(p) }, 80)   // let the compositor drop our view first
    }

    // ------------------------------------------------------------------ capture

    private fun capture(p: MediaProjection) {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        val w = dm.widthPixels
        val h = dm.heightPixels

        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        var vd: VirtualDisplay? = null

        val done = java.util.concurrent.atomic.AtomicBoolean(false)

        reader.setOnImageAvailableListener({ r ->
            if (done.getAndSet(true)) return@setOnImageAvailableListener
            val image = r.acquireLatestImage() ?: run { done.set(false); return@setOnImageAvailableListener }
            try {
                val plane = image.planes[0]
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val rowPadding = rowStride - pixelStride * w

                val bmp = Bitmap.createBitmap(
                    w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)

                val px = IntArray(w * h)
                bmp.getPixels(px, 0, w, 0, 0, w, h)
                bmp.recycle()

                bg.post { solve(flow.detect.Image(w, h, px)) }
            } catch (e: Throwable) {
                Diag.crash(this@AssistService, "capture", e)
                main.post { fail("Capture failed: ${'$'}{e.message}") }
            } finally {
                image.close()
                r.close()
                vd?.release()
            }
        }, bg)

        vd = p.createVirtualDisplay(
            "flowassist", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, main,
        )

        // never hang: if no frame arrives, say so
        main.postDelayed({
            if (!done.get()) {
                done.set(true)
                try { reader.close(); vd?.release() } catch (_: Exception) {}
                fail("no frame captured — is the game using a secure surface?")
            }
        }, 2500)
    }

    // ------------------------------------------------------------------ detect + solve

    private fun solve(img: flow.detect.Image) {
        // ALWAYS dump the frame we actually captured. This is the single most useful artefact when
        // it goes wrong on a real phone — it is the screenshot the detector actually saw.
        Diag.saveShot(this, img)
        Diag.log("captured ${'$'}{img.w}x${'$'}{img.h}")

        val saved = Calibration.load(this)
        val det = try {
            Detector.detect(img, saved?.bounds, saved?.size)
        } catch (e: Throwable) {
            Diag.crash(this, "detect", e)
            main.post { fail("Detection crashed: ${'$'}{e.message}") }
            return
        }

        Diag.report(this, det)

        if (det.board == null) {
            // Refuse to guess. Say what went wrong — a confidently wrong board is worse than none.
            val why = det.problems.firstOrNull()?.what ?: "could not find the board"
            Diag.log("NO BOARD: ${'$'}why")
            main.post { fail("Couldn't read the board:\n${'$'}why") }
            return
        }

        val b = det.board!!
        Diag.log("board ${'$'}{b.rows}x${'$'}{b.cols}, ${'$'}{b.colors.size} colours, " +
                 "${'$'}{b.walls.size} walls, ${'$'}{b.holes.size} holes")

        val sol = try {
            Flow.solve(b, budgetMs = 8_000)
        } catch (e: Throwable) {
            Diag.crash(this, "solve", e)
            main.post { fail("Gave up solving this one") }
            return
        }

        if (sol == null) {
            Diag.log("UNSOLVABLE — board almost certainly misread")
            main.post {
                fail("No solution — the board was probably misread.\nSend me the diagnostics.")
            }
            return
        }

        Diag.log("solved in ${'$'}{sol.elapsedMs}ms")
        main.post {
            try { showSolution(det, sol) }
            catch (e: Throwable) { Diag.crash(this, "overlay", e); fail("Overlay failed: ${'$'}{e.message}") }
        }
    }

    // ------------------------------------------------------------------ overlay

    private fun showSolution(det: Detector.Result, sol: flow.Solution) {
        clearOverlay()

        val v = OverlayView(this, det.grid!!, sol.paths, det.endpointColors)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // ---------------------------------------------------------------------------------
            // FLAG_NOT_TOUCHABLE IS THE WHOLE PRODUCT.
            // Without it this window swallows your finger and you cannot trace the answer it is
            // showing you. The app would look flawless in a screenshot and be useless in the hand.
            // Only the bubble takes touches.
            // ---------------------------------------------------------------------------------
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        wm.addView(v, lp)
        overlay = v

        bubble?.visibility = View.VISIBLE
        (bubble as BubbleView).setBusy(false)
        busy = false
        toast("Solved — trace the arrows. Tap the bubble to clear.")
    }

    private fun clearOverlay() {
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
    }

    private fun fail(msg: String) {
        clearOverlay()
        bubble?.visibility = View.VISIBLE
        (bubble as? BubbleView)?.setBusy(false)
        busy = false
        toast(msg)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------------ plumbing

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Flow Assist", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Flow Assist is running")
            .setContentText("Tap the bubble over Flow Free to solve")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        clearOverlay()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        projection?.stop()
        worker.quitSafely()
        super.onDestroy()
    }
}
