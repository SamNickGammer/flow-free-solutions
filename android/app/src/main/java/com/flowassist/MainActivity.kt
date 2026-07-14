package com.flowassist

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

/**
 * The part you see once. Grant two permissions, flip the switch, then forget this screen exists.
 *
 * Android will not let us ask for these together, so the screen just owns that.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var overlayBtn: Button
    private lateinit var startBtn: Button

    private val PROJECTION_REQ = 7001

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        // last-resort: if anything anywhere throws, write it where we can read it
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Diag.crash(applicationContext, "uncaught on ${'$'}{t.name}", e)
            prev?.uncaughtException(t, e)
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7002)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 96, 56, 56)
            setBackgroundColor(Color.parseColor("#12141A"))
        }

        fun title(s: String) = TextView(this).apply {
            text = s; textSize = 26f; setTextColor(Color.parseColor("#EEF0F6"))
            setPadding(0, 0, 0, 12)
        }
        fun body(s: String) = TextView(this).apply {
            text = s; textSize = 14f; setTextColor(Color.parseColor("#8A90A2"))
            setPadding(0, 0, 0, 28)
        }
        fun button(s: String) = Button(this).apply {
            text = s; isAllCaps = false; textSize = 15f
            setPadding(0, 24, 0, 24)
        }

        root.addView(title("Flow Assist"))
        root.addView(body(
            "Open Flow Free, tap the bubble, and the answer appears over the board with arrows " +
            "showing which way to swipe. You trace it yourself.\n\n" +
            "Runs entirely on your phone. No account, no network."))

        overlayBtn = button("1 · Allow drawing over other apps")
        overlayBtn.setOnClickListener {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        root.addView(overlayBtn)

        startBtn = button("2 · Start — allow screen capture")
        startBtn.setOnClickListener {
            if (!canOverlay()) {
                toast("Grant 'draw over other apps' first")
                return@setOnClickListener
            }
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), PROJECTION_REQ)
        }
        root.addView(startBtn)

        val share = button("Share diagnostics (screenshot + report)")
        share.setOnClickListener { shareDiagnostics() }
        root.addView(share)

        val reset = button("Reset grid calibration")
        reset.setOnClickListener { Calibration.clear(this); toast("Calibration cleared") }
        root.addView(reset)

        status = TextView(this).apply {
            textSize = 13f; setTextColor(Color.parseColor("#8A90A2")); setPadding(0, 36, 0, 0)
        }
        root.addView(status)

        val note = TextView(this).apply {
            text = "v0.1 — manual mode.\n\nReading the board from your screen is the part that " +
                   "hasn't been tested on a real phone yet. If it can't read a level it will say " +
                   "so rather than guess. Tell me what it says and I'll fix it.\n\n" +
                   "Auto-draw is deliberately not here yet: if the grid is off by a few pixels it " +
                   "would swipe the wrong cells and wreck your level."
            textSize = 12f
            setTextColor(Color.parseColor("#5C6272"))
            setPadding(0, 48, 0, 0)
        }
        root.addView(note)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        status.text = if (canOverlay()) "Overlay permission: granted ✓"
                      else "Overlay permission: NOT granted"
    }

    private fun canOverlay(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != PROJECTION_REQ) return
        if (res != Activity.RESULT_OK || data == null) { toast("Screen capture denied"); return }

        val i = Intent(this, AssistService::class.java).apply {
            putExtra(AssistService.EXTRA_RESULT_CODE, res)
            putExtra(AssistService.EXTRA_RESULT_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
        else startService(i)

        toast("Running. Open Flow Free and tap the bubble.")
        moveTaskToBack(true)
    }

    /**
     * Ship me what the phone actually saw: the captured frame and the detection report.
     * Android 11+ blocks the Files app from reaching Android/data, so this has to be a share sheet.
     */
    private fun shareDiagnostics() {
        val dir = getExternalFilesDir(null)
        val files = listOf("shot.png", "report.txt", "log.txt")
            .map { java.io.File(dir, it) }
            .filter { it.exists() }

        if (files.isEmpty()) {
            toast("Nothing yet — tap the bubble over Flow Free once, then come back")
            return
        }

        val uris = ArrayList<android.net.Uri>(files.map {
            androidx.core.content.FileProvider.getUriForFile(this, "${'$'}packageName.files", it)
        })

        val i = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, "Flow Assist diagnostics")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "Send diagnostics"))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
