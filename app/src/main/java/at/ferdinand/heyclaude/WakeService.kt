package at.ferdinand.heyclaude

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.IBinder
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/** Hört im Hintergrund (offline, Vosk) auf "Hey Claude" und öffnet dann den Frage-Dialog. */
class WakeService : Service() {

    companion object {
        @Volatile var running = false
        @Volatile var status = "Gestoppt"
        @Volatile var lastHeard = ""
        @Volatile var wakeAt = 0L

        val WAKE_WORDS = listOf("hey claude", "hi claude", "okay claude", "hey cloud")
        private const val CHANNEL = "wake"

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, WakeService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, WakeService::class.java))
        fun modelDir(ctx: Context) = File(ctx.filesDir, "vosk-model")
        fun modelReady(ctx: Context) = File(modelDir(ctx), "am").isDirectory
    }

    @Volatile private var stopFlag = false
    private var worker: Thread? = null
    private var tone: ToneGenerator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Hey Claude", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val ask = PendingIntent.getActivity(
            this, 1,
            Intent(this, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Hey Claude hört zu")
            .setContentText("Sag „Hey Claude“ oder tippe auf Fragen")
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_btn_speak_now), "Fragen", ask
                ).build()
            )
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        running = true
        try { tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80) } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (worker == null) {
            stopFlag = false
            worker = Thread { loop() }.also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopFlag = true
        running = false
        status = "Gestoppt"
        tone?.release()
        super.onDestroy()
    }

    /** Mikrofon freigeben, solange der Dialog offen ist (Spracherkennung / Wispr brauchen es). */
    private fun shouldPause() = AssistActivity.alive || System.currentTimeMillis() - wakeAt < 4000

    @SuppressLint("MissingPermission")
    private fun loop() {
        var ar: AudioRecord? = null
        try {
            if (!modelReady(this)) {
                status = "Modell fehlt – in der App auf Start tippen"
                stopSelf()
                return
            }
            status = "Lade Spracherkennung …"
            val model = Model(modelDir(this).absolutePath)
            val vocab = WAKE_WORDS.flatMap { it.split(" ") }.distinct()
            val grammar = JSONArray(WAKE_WORDS + vocab + listOf("[unk]")).toString()
            val rec = Recognizer(model, 16000f, grammar)
            rec.setWords(true)

            val minBuf = AudioRecord.getMinBufferSize(
                16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val buf = ShortArray(2048)

            while (!stopFlag) {
                if (shouldPause()) {
                    val old = ar
                    if (old != null) {
                        old.stop(); old.release(); ar = null
                        status = "Pausiert (Dialog offen)"
                    }
                    Thread.sleep(150)
                    continue
                }
                if (ar == null) {
                    val fresh = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(minBuf, 8192) * 2
                    )
                    if (fresh.state != AudioRecord.STATE_INITIALIZED) {
                        fresh.release()
                        status = "Mikrofon belegt – versuche erneut …"
                        Thread.sleep(1000)
                        continue
                    }
                    fresh.startRecording()
                    rec.reset()
                    ar = fresh
                    status = "Hört zu – sag „Hey Claude“"
                }
                val cur = ar!!
                val n = cur.read(buf, 0, buf.size)
                if (n <= 0) continue
                if (rec.acceptWaveForm(buf, n)) {
                    if (isWake(JSONObject(rec.result))) {
                        rec.reset()
                        wakeAt = System.currentTimeMillis()
                        cur.stop(); cur.release(); ar = null
                        onWake()
                    }
                }
            }
            rec.close()
            model.close()
        } catch (e: Exception) {
            status = "Fehler: ${e.message}"
        } finally {
            ar?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        }
    }

    private fun isWake(res: JSONObject): Boolean {
        val arr = res.optJSONArray("result") ?: return false
        val minConf = getSharedPreferences("cfg", MODE_PRIVATE).getFloat("conf", 0.70f)
        val toks = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            o.optString("word") to o.optDouble("conf", 0.0)
        }
        if (toks.isEmpty()) return false
        lastHeard = toks.joinToString(" ") { "${it.first} (${"%.2f".format(it.second)})" }
        for (phrase in WAKE_WORDS) {
            val p = phrase.split(" ")
            for (i in 0..toks.size - p.size) {
                if (p.indices.all { j -> toks[i + j].first == p[j] && toks[i + j].second >= minConf }) return true
            }
        }
        return false
    }

    private fun onWake() {
        try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150) } catch (_: Exception) {}
        startActivity(Intent(this, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
