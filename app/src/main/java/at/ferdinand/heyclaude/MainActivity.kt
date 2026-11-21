package at.ferdinand.heyclaude

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class MainActivity : Activity() {

    companion object {
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var statusTv: TextView
    private lateinit var startBtn: Button
    private lateinit var keyEt: EditText
    private lateinit var modelEt: EditText
    private lateinit var confEt: EditText
    private lateinit var wisprSw: Switch
    private lateinit var speakSw: Switch
    private lateinit var webSw: Switch
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var downloading = false
    @Volatile private var dlStatus = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(refresher)
    }

    private val refresher = object : Runnable {
        override fun run() {
            val s = when {
                downloading -> dlStatus
                WakeService.running -> WakeService.status
                else -> "Gestoppt"
            }
            val heard = WakeService.lastHeard
            statusTv.text = if (heard.isNotEmpty()) "$s\nZuletzt gehört: $heard" else s
            startBtn.text = if (WakeService.running) "Stopp" else "Start"
            ui.postDelayed(this, 1000)
        }
    }

    // ---------------- Oberfläche ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        fun label(t: String, size: Float = 14f, bold: Boolean = false): TextView {
            val tv = TextView(this)
            tv.text = t
            tv.textSize = size
            if (bold) tv.setTypeface(tv.typeface, Typeface.BOLD)
            tv.setPadding(0, dp(12), 0, dp(2))
            col.addView(tv)
            return tv
        }
        fun button(t: String, action: () -> Unit): Button {
            val b = Button(this)
            b.text = t
            b.isAllCaps = false
            b.setOnClickListener { action() }
            col.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return b
        }
        fun edit(value: String, type: Int): EditText {
            val e = EditText(this)
            e.setText(value)
            e.inputType = type
            e.isSingleLine = true
            col.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return e
        }
        fun toggle(t: String, value: Boolean): Switch {
            val s = Switch(this)
            s.text = t
            s.isChecked = value
            s.setPadding(0, dp(10), 0, dp(10))
            col.addView(s, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return s
        }

        label("Hey Claude", 26f, true)
        statusTv = label("", 14f)

        label("Anthropic API-Key", bold = true)
        keyEt = edit(prefs.getString("apiKey", "").orEmpty(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        label("Modell", bold = true)
        modelEt = edit(prefs.getString("model", Claude.DEFAULT_MODEL).orEmpty(), InputType.TYPE_CLASS_TEXT)
        label("Empfindlichkeit „Hey Claude“ (0.4 – 0.9, kleiner = reagiert leichter)", bold = true)
        confEt = edit(prefs.getFloat("conf", 0.70f).toString(),
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)

        wisprSw = toggle("Wispr Flow benutzen (du tippst auf die Wispr-Blase) statt Android-Spracherkennung",
            prefs.getBoolean("wispr", false))
        speakSw = toggle("Antwort vorlesen", prefs.getBoolean("speak", true))
        webSw = toggle("Claude darf im Web suchen (kostet extra)", prefs.getBoolean("web", false))

        button("Speichern") { save(); toast("Gespeichert") }
        startBtn = button("Start") {
            save()
            if (WakeService.running) WakeService.stop(this) else ensureAndStart()
        }
        button("Jetzt fragen (ohne „Hey Claude“)") {
            save()
            startActivity(Intent(this, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        button("Akku: Hey Claude nicht einschränken") { askBatteryExemption() }
        button("Als Assistent für die Seitentaste festlegen") {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }

        label(
            "So geht's: Start tippen, Berechtigungen erlauben (beim ersten Mal wird die Offline-" +
                "Erkennung, ca. 40 MB, geladen). Dann „Hey Claude“ sagen, Piepton abwarten, Frage stellen. " +
                "Die Antwort erscheint unten und wird vorgelesen. „Nachfragen“ behält den Zusammenhang " +
                "5 Minuten lang.\n\nWenn „Hey Claude“ nicht erkannt wird: Empfindlichkeit senken (z. B. 0.55). " +
                "Nach einem Neustart des Handys einmal die App öffnen und auf Start tippen.",
            13f
        )

        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun save() {
        val conf = confEt.text.toString().replace(',', '.').toFloatOrNull()?.coerceIn(0.3f, 0.95f) ?: 0.70f
        prefs.edit()
            .putString("apiKey", keyEt.text.toString().trim())
            .putString("model", modelEt.text.toString().trim().ifEmpty { Claude.DEFAULT_MODEL })
            .putFloat("conf", conf)
            .putBoolean("wispr", wisprSw.isChecked)
            .putBoolean("speak", speakSw.isChecked)
            .putBoolean("web", webSw.isChecked)
            .apply()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    // ---------------- Start mit allen Voraussetzungen ----------------

    private fun ensureAndStart() {
        if (prefs.getString("apiKey", "").isNullOrBlank()) {
            toast("Bitte zuerst den API-Key eintragen")
            return
        }
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            needed += Manifest.permission.POST_NOTIFICATIONS
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 1)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("Bitte „Über anderen Apps anzeigen“ für Hey Claude erlauben, dann zurück und nochmal Start")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (!WakeService.modelReady(this)) {
            downloadModel()
            return
        }
        WakeService.start(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            ensureAndStart()
        } else {
            toast("Ohne Mikrofon geht es nicht")
        }
    }

    private fun askBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Ist schon erlaubt. Bei Samsung zusätzlich: Einstellungen > Akku > Hintergrundnutzung " +
                "> Hey Claude zu „Nie in Standby“ hinzufügen")
            return
        }
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    // ---------------- Offline-Erkennung herunterladen ----------------

    private fun downloadModel() {
        if (downloading) return
        downloading = true
        dlStatus = "Lade Offline-Erkennung …"
        Thread {
            val tmp = File(filesDir, "vosk-model-tmp")
            try {
                tmp.deleteRecursively()
                tmp.mkdirs()
                val c = URL(MODEL_URL).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 60_000
                val total = c.contentLengthLong
                var done = 0L
                val counting = object : java.io.FilterInputStream(c.inputStream) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = super.read(b, off, len)
                        if (n > 0) {
                            done += n
                            if (total > 0) dlStatus = "Lade Offline-Erkennung … ${done * 100 / total} %"
                        }
                        return n
                    }
                }
                val base = tmp.canonicalPath
                ZipInputStream(counting.buffered()).use { zip ->
                    var e = zip.nextEntry
                    while (e != null) {
                        val rel = e.name.substringAfter('/', "")
                        if (rel.isNotEmpty()) {
                            val out = File(tmp, rel)
                            if (!out.canonicalPath.startsWith(base)) throw Exception("Ungültige Datei im Archiv")
                            if (e.isDirectory) out.mkdirs() else {
                                out.parentFile?.mkdirs()
                                FileOutputStream(out).use { zip.copyTo(it) }
                            }
                        }
                        e = zip.nextEntry
                    }
                }
                c.disconnect()
                val target = WakeService.modelDir(this)
                target.deleteRecursively()
                if (!tmp.renameTo(target)) throw Exception("Konnte Modell nicht speichern")
                downloading = false
                runOnUiThread { ensureAndStart() }
            } catch (e: Exception) {
                tmp.deleteRecursively()
                downloading = false
                runOnUiThread { toast("Download fehlgeschlagen: ${e.message}") }
            }
        }.start()
    }
}
