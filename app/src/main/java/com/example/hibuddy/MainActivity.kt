package com.example.hibuddy

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Camera
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private val MODEL = "models/gemini-2.5-flash-native-audio-preview-12-2025"
    private val PERMISSION_REQUEST_CODE = 1001
    private val PREF_KEY_NAME = "user_" + "token_secret"
    private val UPDATE_REPO = "TheKingOfJump/HiBuddy"
    private val WAKE_PHRASES = listOf("hibuddy", "haibuddy", "heibuddy", "aibuddy")
    private val PREF_VOICE = "voice_name"
    private val VOICES = listOf(
        "Aoede", "Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus",
        "Callirrhoe", "Autonoe", "Enceladus", "Iapetus", "Umbriel", "Algieba",
        "Despina", "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Achernar",
        "Alnilam", "Schedar", "Gacrux", "Pulcherrima", "Achird",
        "Zubenelgenubi", "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat"
    )

    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var isRecording = false
    private var isSessionReady = false
    private var isSessionStarting = false

    private lateinit var statusText: TextView
    private lateinit var etApiKey: EditText
    private lateinit var surfaceView: SurfaceView
    private lateinit var tvTranscript: TextView
    private lateinit var transcriptScroll: ScrollView
    private lateinit var etMessage: EditText
    private lateinit var spVoice: Spinner
    private var voiceName = "Aoede"
    private lateinit var cbWake: CheckBox
    private lateinit var btnUpdate: Button
    private var camera: Camera? = null
    private var lastFrameJpeg: ByteArray? = null

    private val transcript = StringBuilder()
    private var transcriptSpeaker: String? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var wakeMode = false
    private var wakeListening = false
    private val wakeHandler = Handler(Looper.getMainLooper())

    private var latestVersionCode: Int? = null
    private var setupAttempt = 0
    @Volatile
    private var lastAudioChunkAt = 0L
    @Volatile
    private var playbackRunning = false
    private val playbackQueue = java.util.concurrent.LinkedBlockingQueue<String>()
    private var lastFrameEncodedAt = 0L

    private val INPUT_SAMPLE_RATE = 16000
    private val OUTPUT_SAMPLE_RATE = 24000
    private var audioTrack: AudioTrack? = null

    private val videoHandler = Handler(Looper.getMainLooper())
    private val videoRunnable = object : Runnable {
        override fun run() {
            if (isConnected) {
                sendCameraFrame()
                videoHandler.postDelayed(this, 1000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("HiBuddyPrefs", Context.MODE_PRIVATE)
        if (prefs.getInt("last_version", 0) != currentVersionCode()) {
            prefs.edit()
                .putInt("last_version", currentVersionCode())
                .putBoolean("start_failed", false)
                .commit()
        }
        val previousStage = prefs.getString("last_stage", null)
        val previousFailed = prefs.getBoolean("start_failed", false)
        markStart("inizio")
        installCrashLogger()
        if (previousFailed) {
            showSafeMode(previousStage ?: "sconosciuto")
            return
        }
        try {
            initUi()
            showLastCrashIfAny()
            markStart("avvio completato")
            prefs.edit().putBoolean("start_failed", false).commit()
        } catch (t: Throwable) {
            prefs.edit().putBoolean("start_failed", true).commit()
            showFatalError(t)
        }
    }

    private fun markStart(stage: String) {
        getSharedPreferences("HiBuddyPrefs", Context.MODE_PRIVATE)
            .edit().putString("last_stage", stage).commit()
    }

    private fun showSafeMode(stage: String) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val crashText = try {
            File(filesDir, "last_crash.txt").readText()
        } catch (t: Throwable) {
            ""
        }
        container.addView(TextView(this).apply {
            text = "L'avvio precedente si e' interrotto durante: $stage\n\n" +
                "L'app e' partita in modalita' sicura, senza fotocamera e senza controllo aggiornamenti. " +
                "Premi il pulsante per riprovare l'avvio normale.\n\n" +
                (if (crashText.isBlank()) "" else "Dettaglio:\n$crashText")
            setTextColor(Color.WHITE)
            textSize = 12f
            setTextIsSelectable(true)
        })
        container.addView(Button(this).apply {
            text = "Riprova avvio normale"
            setOnClickListener {
                getSharedPreferences("HiBuddyPrefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("start_failed", false).commit()
                recreate()
            }
        })
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(container)
        }
        setContentView(scroll)
    }

    private fun initUi() {
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        etApiKey = findViewById(R.id.etApiKey)
        surfaceView = findViewById(R.id.cameraPreview)
        tvTranscript = findViewById(R.id.tvTranscript)
        transcriptScroll = findViewById(R.id.transcriptScroll)
        etMessage = findViewById(R.id.etMessage)
        spVoice = findViewById(R.id.spVoice)
        cbWake = findViewById(R.id.cbWake)
        btnUpdate = findViewById(R.id.btnUpdate)

        val versionLabel = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (t: Throwable) {
            "?"
        }

        val prefs = getSharedPreferences("HiBuddyPrefs", Context.MODE_PRIVATE)

        val voiceAdapter = ArrayAdapter(this, R.layout.spinner_item, VOICES)
        voiceAdapter.setDropDownViewResource(R.layout.spinner_item)
        spVoice.adapter = voiceAdapter
        voiceName = prefs.getString(PREF_VOICE, "Aoede") ?: "Aoede"
        spVoice.setSelection(VOICES.indexOf(voiceName).coerceAtLeast(0))
        spVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                voiceName = VOICES[position]
                prefs.edit().putString(PREF_VOICE, voiceName).apply()
                if (isConnected) updateStatus("Voce $voiceName: vale dalla prossima sessione")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val savedKey = prefs.getString(PREF_KEY_NAME, "") ?: ""
        if (savedKey.isNotEmpty()) {
            etApiKey.setText(savedKey)
            updateStatus("Pronto v$versionLabel (chiave salvata)")
        } else {
            updateStatus("Inserisci e salva la tua API Key in alto (v$versionLabel)")
        }

        findViewById<Button>(R.id.btnSaveKey).setOnClickListener {
            val newKey = etApiKey.text.toString().trim()
            if (newKey.isNotEmpty()) {
                prefs.edit().putString(PREF_KEY_NAME, newKey).apply()
                updateStatus("API Key salvata sul telefono!")
            } else {
                updateStatus("Campo vuoto: incolla una chiave valida")
            }
        }

        checkAndRequestPermissions()
        setupAudioOutput()

        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            if (!hasRequiredPermissions()) {
                checkAndRequestPermissions()
                return@setOnClickListener
            }
            if (!isConnected) startSession() else stopSession()
        }

        findViewById<Button>(R.id.btnSendFrame).setOnClickListener {
            sendCameraFrame()
        }

        cbWake.setOnCheckedChangeListener { _, isChecked ->
            wakeMode = isChecked
            if (isChecked) {
                startWakeListening()
            } else {
                stopWakeListening()
                updateStatus("Ascolto \"HiBuddy\" disattivato")
            }
        }

        findViewById<Button>(R.id.btnSendText).setOnClickListener { sendTextMessage() }
        etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTextMessage()
                true
            } else {
                false
            }
        }

        btnUpdate.setOnClickListener { downloadAndInstallUpdate() }

        checkForUpdate()
    }

    private fun hasRequiredPermissions(): Boolean {
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val cam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        return mic && cam
    }

    private fun checkAndRequestPermissions() {
        if (!hasRequiredPermissions()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA),
                PERMISSION_REQUEST_CODE
            )
        } else {
            setupCamera()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && hasRequiredPermissions()) {
            setupCamera()
            updateStatus("Permessi concessi! Pronto.")
        } else {
            updateStatus("Permessi Microfono/Fotocamera necessari")
        }
    }

    private fun startSession() {
        val prefs = getSharedPreferences("HiBuddyPrefs", Context.MODE_PRIVATE)
        val userToken = prefs.getString(PREF_KEY_NAME, "")?.trim() ?: ""
        if (userToken.isEmpty()) {
            updateStatus("Inserisci e salva prima la API Key in alto")
            return
        }

        if (audioTrack == null) setupAudioOutput()
        setupAttempt = 0
        isSessionStarting = true
        stopWakeListening()
        clearTranscript()
        updateStatus("Cerco un modello Live disponibile...")
        thread {
            val model = resolveLiveModel(userToken)
            runOnUiThread { connectWebSocket(userToken, model) }
        }
    }

    private fun resolveLiveModel(token: String): String {
        val liveModels = try {
            fetchLiveModels(token)
        } catch (t: Throwable) {
            emptyList()
        }
        if (liveModels.isEmpty() || MODEL in liveModels) return MODEL
        return liveModels.firstOrNull { it.contains("native-audio") }
            ?: liveModels.firstOrNull {
                it.contains("-live") && !it.contains("translate") &&
                    !it.contains("transcribe") && !it.contains("thinking")
            }
            ?: liveModels.first()
    }

    private fun fetchLiveModels(token: String): List<String> {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$token&pageSize=1000"
        return client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val models = JSONObject(body).optJSONArray("models") ?: return emptyList()
            val result = mutableListOf<String>()
            for (i in 0 until models.length()) {
                val model = models.getJSONObject(i)
                val methods = model.optJSONArray("supportedGenerationMethods") ?: continue
                val name = model.optString("name")
                if (name.isEmpty()) continue
                for (j in 0 until methods.length()) {
                    if (methods.optString(j) == "bidiGenerateContent") {
                        result.add(name)
                        break
                    }
                }
            }
            result
        }
    }

    private fun connectWebSocket(
        userToken: String,
        model: String,
        apiVersion: String = "v1beta",
        minimalSetup: Boolean = false
    ) {
        val wsUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.$apiVersion" +
            ".GenerativeService.BidiGenerateContent?key=$userToken"

        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                try {
                    isConnected = true
                    isSessionStarting = false
                    updateStatus("Configuro ${model.removePrefix("models/")}...")
                    debugLog("connessione aperta ($apiVersion)")
                    sendInitialSetup(ws, model, minimalSetup)
                    videoHandler.postDelayed({
                        if (isSessionReady) return@postDelayed
                        setupAttempt++
                        when (setupAttempt) {
                            1 -> {
                                debugLog("nessuna risposta: riprovo con v1alpha")
                                closeQuietly(ws)
                                connectWebSocket(userToken, model, "v1alpha", false)
                            }
                            2 -> {
                                debugLog("nessuna risposta: riprovo con configurazione minima")
                                closeQuietly(ws)
                                connectWebSocket(userToken, model, "v1alpha", true)
                            }
                            else -> {
                                debugLog("nessuna risposta nemmeno con la configurazione minima")
                                updateStatus("Il server non risponde: guarda il riquadro")
                            }
                        }
                    }, 10000L)
                } catch (t: Throwable) {
                    debugLog("errore all'apertura: ${t.message}")
                    updateStatus("Errore avvio sessione: ${t.message}")
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                logServerMessage(text)
                try {
                    handleServerMessage(text)
                } catch (t: Throwable) {
                    debugLog("errore lettura risposta: ${t.message}")
                }
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                val text = bytes.utf8()
                logServerMessage(text)
                try {
                    handleServerMessage(text)
                } catch (t: Throwable) {
                    debugLog("errore lettura risposta: ${t.message}")
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (ws !== webSocket) return
                isConnected = false
                isRecording = false
                isSessionReady = false
                isSessionStarting = false
                videoHandler.removeCallbacks(videoRunnable)
                updateStatus("Errore: ${t.message}")
                debugLog("connessione fallita: ${t.message} (http ${response?.code ?: "-"})")
                val body = try {
                    response?.body?.string()
                } catch (e: Throwable) {
                    null
                }
                if (!body.isNullOrBlank()) debugLog("dettaglio: " + body.take(400))
                maybeResumeWake()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (ws !== webSocket) return
                isConnected = false
                isRecording = false
                isSessionReady = false
                isSessionStarting = false
                videoHandler.removeCallbacks(videoRunnable)
                updateStatus("Disconnesso ($reason)")
                debugLog("chiuso dal server: $code $reason")
                maybeResumeWake()
            }
        })
    }

    private fun closeQuietly(ws: WebSocket) {
        try {
            ws.close(1000, "retry")
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    private fun logServerMessage(text: String) {
        if (text.contains("inlineData")) {
            if (!isSessionReady) debugLog("server: audio (${text.length} caratteri)")
        } else {
            debugLog("server: " + text.take(400))
        }
    }

    private fun stopSession() {
        isConnected = false
        isRecording = false
        isSessionReady = false
        isSessionStarting = false
        videoHandler.removeCallbacks(videoRunnable)
        webSocket?.close(1000, "Chiusura utente")
        webSocket = null
        playbackRunning = false
        playbackQueue.clear()
        audioTrack?.pause()
        audioTrack?.flush()
        audioTrack?.release()
        audioTrack = null
        updateStatus("Sessione terminata")
        maybeResumeWake()
    }
    private fun sendInitialSetup(ws: WebSocket, model: String, minimal: Boolean = false) {
        val setup = JSONObject().apply {
            put("model", model)
            if (!minimal) {
                put("inputAudioTranscription", JSONObject())
                put("outputAudioTranscription", JSONObject())
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", voiceName)
                            })
                        })
                    })
                })
                put("tools", JSONArray().put(JSONObject().apply {
                    put("googleSearch", JSONObject())
                }))
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().apply {
                        put("text", "Sei HiBuddy, un assistente personale italiano conciso, amichevole e utile. Rispondi sempre in italiano a voce.")
                    }))
                })
            } else {
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                })
            }
        }
        val payload = JSONObject().put("setup", setup)
        debugLog("setup: " + payload.toString().take(220))
        val sent = ws.send(payload.toString())
        debugLog(if (sent) "setup inviato (voce $voiceName)" else "INVIO SETUP FALLITO")
    }

    private fun startAudioStreaming() {
        isRecording = true
        thread {
            try {
            val minBuffer = AudioRecord.getMinBufferSize(
                INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return@thread
            }
            val bufferSize = if (minBuffer > 0) minBuffer else INPUT_SAMPLE_RATE
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            val echoCanceller = try {
                AcousticEchoCanceler.create(recorder.audioSessionId)
            } catch (t: Throwable) {
                null
            }
            try {
                echoCanceller?.enabled = true
            } catch (t: Throwable) {
                t.printStackTrace()
            }

            val buffer = ByteArray(bufferSize)
            recorder.startRecording()

            while (isRecording && isConnected) {
                val read = recorder.read(buffer, 0, buffer.size)
                val modelIsTalking = SystemClock.elapsedRealtime() - lastAudioChunkAt < 1200L
                if (read > 0 && !modelIsTalking) {
                    val base64Audio = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                    val msg = JSONObject().apply {
                        put("realtimeInput", JSONObject().apply {
                            put("audio", JSONObject().apply {
                                put("mimeType", "audio/pcm;rate=16000")
                                put("data", base64Audio)
                            })
                        })
                    }
                    webSocket?.send(msg.toString())
                }
            }
            try {
                recorder.stop()
                recorder.release()
                echoCanceller?.release()
            } catch (t: Throwable) {
                t.printStackTrace()
            }
            } catch (t: Throwable) {
                isRecording = false
                updateStatus("Errore microfono: ${t.message}")
            }
        }
    }

    private fun handleServerMessage(jsonString: String) {
        try {
            val json = JSONObject(jsonString)

            if (json.has("setupComplete")) {
                isSessionReady = true
                updateStatus("Connesso! Parla pure...")
                startAudioStreaming()
                videoHandler.post(videoRunnable)
                return
            }

            val apiError = json.optJSONObject("error")
            if (apiError != null) {
                updateStatus("Errore API: ${apiError.optString("message")}")
                return
            }

            val serverContent = json.optJSONObject("serverContent") ?: return

            serverContent.optJSONObject("inputTranscription")?.optString("text")?.let {
                if (it.isNotEmpty()) appendTranscript("Tu", it)
            }
            serverContent.optJSONObject("outputTranscription")?.optString("text")?.let {
                if (it.isNotEmpty()) appendTranscript("HiBuddy", it)
            }

            if (serverContent.optBoolean("interrupted", false)) {
                playbackQueue.clear()
                audioTrack?.pause()
                audioTrack?.flush()
                audioTrack?.play()
                return
            }

            val modelTurn = serverContent.optJSONObject("modelTurn") ?: return
            val parts = modelTurn.optJSONArray("parts") ?: return

            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                val inlineData = part.optJSONObject("inlineData")
                if (inlineData != null) {
                    val mimeType = inlineData.optString("mimeType")
                    if (mimeType.startsWith("audio/pcm")) {
                        lastAudioChunkAt = SystemClock.elapsedRealtime()
                        if (playbackQueue.size > 400) playbackQueue.clear()
                        playbackQueue.offer(inlineData.getString("data"))
                    }
                }
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    private fun sendCameraFrame() {
        val jpegData = lastFrameJpeg ?: return
        if (!isConnected || !isSessionReady) return

        val base64Img = Base64.encodeToString(jpegData, Base64.NO_WRAP)
        val msg = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("video", JSONObject().apply {
                    put("mimeType", "image/jpeg")
                    put("data", base64Img)
                })
            })
        }
        webSocket?.send(msg.toString())
    }

    // AudioTrack.Builder and AudioFormat.Builder only exist from Android 6.0 (API 23);
    // the plain constructor below works on every supported Android version.
    @Suppress("DEPRECATION")
    private fun setupAudioOutput() {
        try {
            val minBuffer = AudioTrack.getMinBufferSize(
                OUTPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(if (minBuffer > 0) minBuffer * 4 else 0, OUTPUT_SAMPLE_RATE * 2)
            audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                OUTPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM
            )
            audioTrack?.play()
            startPlaybackThread()
        } catch (t: Throwable) {
            audioTrack = null
            updateStatus("Audio in uscita non disponibile: ${t.message}")
        }
    }

    private fun startPlaybackThread() {
        if (playbackRunning) return
        playbackRunning = true
        thread {
            while (playbackRunning) {
                val data = try {
                    playbackQueue.poll(300, TimeUnit.MILLISECONDS)
                } catch (t: Throwable) {
                    null
                } ?: continue
                val track = audioTrack ?: continue
                try {
                    val bytes = Base64.decode(data, Base64.DEFAULT)
                    val written = track.write(bytes, 0, bytes.size)
                    if (written < 0) debugLog("errore scrittura audio: $written")
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun setupCamera() {
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                try {
                    camera = Camera.open()
                    camera?.setDisplayOrientation(90)
                    camera?.setPreviewDisplay(holder)
                    camera?.setPreviewCallback { data, cam ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastFrameEncodedAt < 900L) return@setPreviewCallback
                        lastFrameEncodedAt = now
                        try {
                            val size = cam.parameters.previewSize
                            val yuv = YuvImage(data, ImageFormat.NV21, size.width, size.height, null)
                            val out = ByteArrayOutputStream()
                            yuv.compressToJpeg(Rect(0, 0, size.width, size.height), 55, out)
                            lastFrameJpeg = out.toByteArray()
                        } catch (t: Throwable) {
                            t.printStackTrace()
                        }
                    }
                    camera?.startPreview()
                } catch (t: Throwable) {
                    updateStatus("Errore fotocamera: ${t.message}")
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                camera?.stopPreview()
                camera?.release()
                camera = null
            }
        })
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                File(filesDir, "last_crash.txt").writeText(Log.getStackTraceString(throwable))
            } catch (t: Throwable) {
                t.printStackTrace()
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun showLastCrashIfAny() {
        val file = File(filesDir, "last_crash.txt")
        if (!file.exists()) return
        val text = try {
            file.readText()
        } catch (t: Throwable) {
            ""
        }
        file.delete()
        if (text.isBlank()) return
        val view = TextView(this).apply {
            this.text = "Ultimo errore registrato:\n\n$text"
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(24, 24, 24, 24)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("HiBuddy ha avuto un problema")
            .setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showFatalError(t: Throwable) {
        val message = Log.getStackTraceString(t)
        try {
            File(filesDir, "last_crash.txt").writeText(message)
        } catch (t: Throwable) {
            t.printStackTrace()
        }
        val view = TextView(this).apply {
            this.text = "Errore all'avvio di HiBuddy:\n\n$message"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(24, 24, 24, 24)
        }
        setContentView(view)
    }

    private fun updateStatus(text: String) {
        if (!::statusText.isInitialized) return
        runOnUiThread {
            statusText.text = text
        }
    }

    private fun sendTextMessage() {
        val text = etMessage.text.toString().trim()
        if (text.isEmpty()) return
        if (!isConnected || !isSessionReady) {
            updateStatus("Prima premi \"Parla con AI\" per aprire la sessione")
            return
        }
        val message = JSONObject().apply {
            put("clientContent", JSONObject().apply {
                put("turns", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().apply { put("text", text) }))
                }))
                put("turnComplete", true)
            })
        }
        webSocket?.send(message.toString())
        appendUserText(text)
        etMessage.setText("")
    }

    private fun debugLog(line: String) {
        if (!::tvTranscript.isInitialized) return
        runOnUiThread {
            if (transcript.isNotEmpty()) transcript.append('\n')
            transcript.append("\u2022 ").append(line)
            transcriptSpeaker = null
            tvTranscript.text = transcript.toString()
            transcriptScroll.post { transcriptScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun appendUserText(text: String) {
        runOnUiThread {
            if (transcript.isNotEmpty()) transcript.append('\n')
            transcript.append("Tu: ").append(text)
            transcriptSpeaker = null
            tvTranscript.text = transcript.toString()
            transcriptScroll.post { transcriptScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun appendTranscript(speaker: String, chunk: String) {
        runOnUiThread {
            if (transcriptSpeaker != speaker) {
                if (transcript.isNotEmpty()) transcript.append('\n')
                transcript.append(speaker).append(": ")
                transcriptSpeaker = speaker
            }
            transcript.append(chunk)
            tvTranscript.text = transcript.toString()
            transcriptScroll.post { transcriptScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun clearTranscript() {
        runOnUiThread {
            transcript.setLength(0)
            transcriptSpeaker = null
            tvTranscript.text = ""
        }
    }

    private fun startWakeListening() {
        if (!wakeMode || wakeListening || isConnected || isSessionStarting) return
        if (!hasRequiredPermissions()) {
            updateStatus("Servono i permessi microfono per l'ascolto")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateStatus("Riconoscimento vocale non disponibile sul telefono")
            return
        }

        speechRecognizer?.destroy()
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                wakeListening = false
                scheduleWakeRestart()
            }

            override fun onResults(results: Bundle?) {
                wakeListening = false
                checkWakePhrase(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
                scheduleWakeRestart()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                checkWakePhrase(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        wakeListening = true
        updateStatus("In ascolto: dì \"HiBuddy\"")
        recognizer.startListening(intent)
    }

    private fun checkWakePhrase(candidates: List<String>?) {
        val heard = candidates?.any { text ->
            val normalized = text.lowercase().replace(Regex("[^a-z]"), "")
            WAKE_PHRASES.any { normalized.contains(it) }
        } ?: false
        if (!heard) return

        stopWakeListening()
        beep()
        updateStatus("HiBuddy ti ha sentito!")
        startSession()
    }

    private fun scheduleWakeRestart() {
        if (!wakeMode || isConnected || isSessionStarting) return
        wakeHandler.postDelayed({
            if (wakeMode && !isConnected && !isSessionStarting) startWakeListening()
        }, 800L)
    }

    private fun stopWakeListening() {
        wakeListening = false
        wakeHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun maybeResumeWake() {
        if (!wakeMode || isSessionStarting) return
        wakeHandler.postDelayed({
            if (wakeMode && !isConnected && !isSessionStarting) startWakeListening()
        }, 600L)
    }

    private fun beep() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 400L)
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    private fun checkForUpdate() {
        thread {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url("https://api.github.com/repos/$UPDATE_REPO/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string() ?: return@use
                    val tag = JSONObject(body).optString("tag_name")
                    val latest = tag.filter { it.isDigit() }.toIntOrNull() ?: return@use
                    if (latest > currentVersionCode()) {
                        latestVersionCode = latest
                        runOnUiThread {
                            btnUpdate.text = "Aggiorna a $latest"
                            btnUpdate.visibility = View.VISIBLE
                        }
                    }
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun currentVersionCode(): Int {
        val info = packageManager.getPackageInfo(packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            info.versionCode
        }
    }

    private fun downloadAndInstallUpdate() {
        val version = latestVersionCode ?: return
        btnUpdate.isEnabled = false
        updateStatus("Scarico la versione $version...")
        thread {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url("https://github.com/$UPDATE_REPO/releases/download/v$version/app-debug.apk")
                    .build()
                val dir = File(cacheDir, "updates").apply { mkdirs() }
                val apk = File(dir, "hibuddy-$version.apk")
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        updateStatus("Download non riuscito (${response.code})")
                        runOnUiThread { btnUpdate.isEnabled = true }
                        return@use
                    }
                    response.body?.byteStream()?.use { input ->
                        apk.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                runOnUiThread { installApk(apk) }
            } catch (t: Throwable) {
                updateStatus("Errore aggiornamento: ${t.message}")
                runOnUiThread { btnUpdate.isEnabled = true }
            }
        }
    }

    private fun installApk(apk: File) {
        btnUpdate.isEnabled = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            updateStatus("Consenti l'installazione, poi tocca di nuovo Aggiorna")
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            updateStatus("Completa l'installazione dalla finestra di Android")
        } catch (t: Throwable) {
            updateStatus("Impossibile avviare l'installazione: ${t.message}")
        }
    }

    override fun onDestroy() {
        wakeMode = false
        stopWakeListening()
        stopSession()
        audioTrack?.release()
        super.onDestroy()
    }
}
