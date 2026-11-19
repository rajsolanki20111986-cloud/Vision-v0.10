package com.vision.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/** Shared live state read by the UI. */
object VisionCore {
    val state = MutableStateFlow(LiveState.Disconnected)
    val caption = MutableStateFlow("")
    /** Microphone and speaker switches shown on the Voice screen. */
    val micOn = MutableStateFlow(true)
    val speakerOn = MutableStateFlow(true)
}

/**
 * Foreground (microphone) service for voice.
 *
 * v0.10: the phone listens first (Android speech recognition, no Gemini). If what he said is a plain command
 * (song, seek, torch, volume, call, alarm, lock, ...), FastPath runs it at once. Only when it is a real
 * conversation or a hard task does the Gemini Live session start, and it is given the words he already said,
 * so he never has to repeat. If on-phone recognition is not available, it goes straight to Gemini Live as before.
 */
class VoiceService : Service() {
    private var session: LiveSession? = null
    private var lastUser: Boolean? = null
    private var buf = ""

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var stopped = false
    @Volatile private var inLive = false
    private var rounds = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Prefs.init(this)
        val n = notification()
        if (Build.VERSION.SDK_INT >= 30) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)

        stopped = false
        inLive = false
        rounds = 0
        VisionCore.state.value = LiveState.Connecting
        session?.stop()
        session = null
        if (tts == null) {
            tts = TextToSpeech(this) { status -> ttsReady = status == TextToSpeech.SUCCESS }
        }
        listenLocal(first = true)
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ on-phone listening

    private fun createRecognizer(): SpeechRecognizer {
        // Name Google's recognizer: when Vision is the chosen assistant, the default recognizer would be Vision's own empty one.
        val google = ComponentName(
            "com.google.android.googlequicksearchbox",
            "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
        )
        return runCatching { SpeechRecognizer.createSpeechRecognizer(this, google) }.getOrNull()
            ?: SpeechRecognizer.createSpeechRecognizer(this)
    }

    private fun listenLocal(first: Boolean = false) {
        main.post {
            if (stopped || inLive) return@post
            runCatching { recognizer?.destroy() }
            val r = try { createRecognizer() } catch (e: Exception) { null }
            if (r == null) { connectLive(null); return@post }
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { VisionCore.state.value = LiveState.Listening }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {
                    val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!t.isNullOrBlank()) VisionCore.caption.value = "You: $t"
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onResults(results: Bundle?) {
                    val hyps = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        .filter { it.isNotBlank() }
                    if (hyps.isEmpty()) { onSilence(first); return }
                    scope.launch { handleHeard(hyps) }
                }
                override fun onError(error: Int) {
                    when (error) {
                        // He said nothing: stop quietly (first press) or after the last command.
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> onSilence(first)
                        // Heard something it could not turn into words: let Gemini listen instead.
                        SpeechRecognizer.ERROR_NO_MATCH -> if (first) connectLive(null) else finish()
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> finish()
                        // Recognizer missing, busy or broken: use Gemini Live as in v0.9.
                        else -> connectLive(null)
                    }
                }
            })
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 4)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 600L)
            try { r.startListening(i) } catch (e: Exception) { connectLive(null) }
        }
    }

    private fun onSilence(first: Boolean) {
        // Pressed the button but said nothing: close. After a command: close too.
        finish()
    }

    private suspend fun handleHeard(hyps: List<String>) {
        // Try the best guess first, then the other guesses of the recognizer.
        for (h in hyps) {
            val reply = try { FastPath.tryHandle(this, h) } catch (e: Exception) { null }
            if (reply != null) {
                MemoryStore.addTurn("voice", true, h)
                MemoryStore.addTurn("voice", false, reply)
                VisionCore.caption.value = "You: $h\nVision: $reply"
                main.post { speakThenListen(reply) }
                return
            }
        }
        // Not a plain command: a conversation or a harder task. Gemini takes over with his words.
        val text = hyps.first()
        MemoryStore.addTurn("voice", true, text)
        VisionCore.caption.value = "You: $text"
        main.post { connectLive(text) }
    }

    private fun speakThenListen(text: String) {
        if (stopped) return
        if (++rounds > 8) { finish(); return }
        val t = tts
        if (!ttsReady || t == null || !VisionCore.speakerOn.value) { listenLocal(); return }
        t.setLanguage(if (LanguageDetector.detectLanguage(text) == "hi") Locale("hi", "IN") else Locale.ENGLISH)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { VisionCore.state.value = LiveState.Speaking }
            override fun onDone(utteranceId: String?) { listenLocal() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { listenLocal() }
        })
        t.speak(text.take(240), TextToSpeech.QUEUE_FLUSH, null, "vision-reply")
    }

    // ------------------------------------------------------------------ Gemini Live (conversation, hard tasks)

    private fun connectLive(initial: String?) {
        if (inLive || stopped) return
        inLive = true
        runCatching { recognizer?.destroy() }
        recognizer = null
        runCatching { tts?.stop() }
        session?.stop()
        session = LiveSession(
            this,
            onState = { s ->
                VisionCore.state.value = s
                if (s == LiveState.Disconnected) stopSelf()
            },
            onCaption = { text, user ->
                buf = if (lastUser == user) buf + text else text
                lastUser = user
                VisionCore.caption.value = (if (user) "You: " else "Vision: ") + buf
            }
        ).also {
            it.initialText = initial
            it.connect(Prefs.apiKey, Prefs.voice)
        }
    }

    private fun finish() {
        main.post { stopSelf() }
    }

    override fun onDestroy() {
        stopped = true
        runCatching { recognizer?.destroy() }
        recognizer = null
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
        scope.cancel()
        session?.stop()
        VisionCore.state.value = LiveState.Disconnected
        // Go back to listening for "Jarvis" once the voice session ends.
        if (Config.WAKE_WORD_ENABLED && Config.PICOVOICE_KEY.isNotBlank()) {
            runCatching { startForegroundService(Intent(this, WakeWordService::class.java)) }
        }
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("vision", "Vision", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "vision")
            .setContentTitle("Vision is listening")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
