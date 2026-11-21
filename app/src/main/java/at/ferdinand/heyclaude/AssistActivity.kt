package at.ferdinand.heyclaude

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/** Der Frage-/Antwort-Dialog: Frage aufnehmen -> Claude -> Antwort anzeigen + vorlesen. */
class AssistActivity : Activity() {

    companion object {
        @Volatile var alive = false
        val history = mutableListOf<Pair<String, String>>()
        var lastTime = 0L
        private const val CONTEXT_MS = 5 * 60_000L
        private const val WISPR_STABLE_MS = 1800L
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var statusTv: TextView
    private lateinit var input: EditText
    private lateinit var answerTv: TextView
    private val ui = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeak: String? = null

    private var watching = false
    private var lastChange = 0L
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alive = true
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        buildUi()
        window.setGravity(Gravity.BOTTOM)
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        setFinishOnTouchOutside(true)

        tts = TextToSpeech(this) { s ->
            if (s == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale.getDefault())
                ttsReady = true
                pendingSpeak?.let { speak(it) }
                pendingSpeak = null
            }
        }
        if (System.currentTimeMillis() - lastTime > CONTEXT_MS) history.clear()
        // kurz warten, bis der Hintergrund-Dienst das Mikrofon freigegeben hat
        ui.postDelayed({ startInput() }, 400)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        startInput()
    }

    override fun onStop() {
        super.onStop()
        // App verlassen / Bildschirm aus -> Dialog schließen, damit "Hey Claude" wieder lauscht
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        alive = false
        watching = false
        ui.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    // ---------------- Oberfläche ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(10))
        }
        statusTv = TextView(this).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFD97757.toInt())
        }
        input = EditText(this).apply {
            hint = "Deine Frage …"
            textSize = 16f
            maxLines = 4
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { lastChange = System.currentTimeMillis() }
        })
        answerTv = TextView(this).apply {
            textSize = 16f
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(8))
        }
        val scroll = ScrollView(this).apply { addView(answerTv) }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        fun addBtn(label: String, action: () -> Unit) {
            val b = Button(this)
            b.text = label
            b.isAllCaps = false
            b.textSize = 13f
            b.setOnClickListener { action() }
            buttons.addView(b)
        }
        addBtn("Senden") {
            val t = input.text.toString().trim()
            if (t.isNotEmpty()) submit(t)
        }
        addBtn("Nachfragen") { startInput() }
        addBtn("Stopp") { tts?.stop() }
        addBtn("Schließen") { finish() }

        root.addView(statusTv)
        root.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
    }

    // ---------------- Frage aufnehmen ----------------

    private fun startInput() {
        tts?.stop()
        busy = false
        watching = false
        recognizer?.cancel()
        input.setText("")
        answerTv.text = ""
        if (prefs.getBoolean("wispr", false)) startWispr() else startRecognizer()
    }

    /** Modus 1: Android-Spracherkennung – komplett freihändig. */
    private fun startRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusTv.text = "Keine Spracherkennung verfügbar – Wispr-Modus in der App einschalten"
            return
        }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { statusTv.text = "🎙  Sprich jetzt …" }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { statusTv.text = "⏳  Verarbeite …" }
            override fun onError(error: Int) {
                if (busy) return
                statusTv.text = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "Nichts verstanden – tippe auf Nachfragen"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mikrofon-Berechtigung fehlt"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Kein Internet"
                    else -> "Spracherkennung-Fehler ($error) – tippe auf Nachfragen"
                }
            }
            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (t.isNotBlank()) {
                    input.setText(t)
                    submit(t)
                } else {
                    statusTv.text = "Nichts verstanden – tippe auf Nachfragen"
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                    if (it.isNotBlank()) input.setText(it)
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        statusTv.text = "🎙  Einen Moment …"
        r.startListening(i)
    }

    /** Modus 2: Wispr Flow – Tastatur geht auf, du tippst auf die Wispr-Blase und sprichst. */
    private fun startWispr() {
        statusTv.text = "Tippe auf die Wispr-Blase und sprich"
        input.requestFocus()
        ui.postDelayed({
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, 0)
        }, 250)
        watching = true
        lastChange = System.currentTimeMillis()
        ui.postDelayed(watcher, 300)
    }

    /** Sobald Wispr den Text eingefügt hat und er sich nicht mehr ändert -> automatisch senden. */
    private val watcher = object : Runnable {
        override fun run() {
            if (!watching) return
            val t = input.text.toString().trim()
            if (t.isNotEmpty() && System.currentTimeMillis() - lastChange > WISPR_STABLE_MS) {
                submit(t)
                return
            }
            ui.postDelayed(this, 300)
        }
    }

    // ---------------- Claude ----------------

    private fun submit(prompt: String) {
        if (busy) return
        busy = true
        watching = false
        recognizer?.cancel()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)

        val key = prefs.getString("apiKey", "").orEmpty().trim()
        if (key.isEmpty()) {
            statusTv.text = "Kein API-Key – bitte in der App eintragen"
            busy = false
            return
        }
        statusTv.text = "Claude denkt nach …"
        answerTv.text = ""
        history.add("user" to prompt)
        while (history.size > 12) history.removeAt(0)
        while (history.isNotEmpty() && history[0].first != "user") history.removeAt(0)
        val msgs = history.toList()
        val model = prefs.getString("model", Claude.DEFAULT_MODEL).orEmpty().trim().ifEmpty { Claude.DEFAULT_MODEL }
        val web = prefs.getBoolean("web", false)

        Thread {
            try {
                val answer = Claude.ask(key, model, msgs, web)
                runOnUiThread { if (alive) showAnswer(answer) }
            } catch (e: Exception) {
                runOnUiThread {
                    if (history.isNotEmpty() && history.last().first == "user") history.removeAt(history.size - 1)
                    statusTv.text = "Fehler"
                    answerTv.text = e.message ?: e.toString()
                    busy = false
                }
            }
        }.start()
    }

    private fun showAnswer(answer: String) {
        history.add("assistant" to answer)
        lastTime = System.currentTimeMillis()
        statusTv.text = "Claude"
        answerTv.text = answer
        busy = false
        if (prefs.getBoolean("speak", true)) speak(answer)
    }

    private fun speak(text: String) {
        val clean = text.replace(Regex("[*_#`>|~]"), "")
        val engine = tts
        if (!ttsReady || engine == null) {
            pendingSpeak = clean
            return
        }
        // TTS hat ein Längenlimit -> in Stücke teilen
        val max = 3500
        var rest = clean
        var first = true
        while (rest.isNotEmpty()) {
            var cut = if (rest.length <= max) rest.length else rest.lastIndexOf(". ", max).let { if (it < 500) max else it + 1 }
            if (cut <= 0) cut = minOf(max, rest.length)
            val part = rest.substring(0, cut)
            rest = rest.substring(cut).trimStart()
            engine.speak(part, if (first) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "a${part.hashCode()}")
            first = false
        }
    }
}
