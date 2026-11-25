package com.example.hibuddy

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Camera
import android.media.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import org.conscrypt.Conscrypt
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.Security
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private val MODEL = "models/gemini-2.5-flash-native-audio-preview-12-2025"
    private val PERMISSION_REQUEST_CODE = 1001

    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var isRecording = false

    private lateinit var statusText: TextView
    private lateinit var etApiKey: EditText
    private lateinit var surfaceView: SurfaceView
    private var camera: Camera? = null
    private var lastFrameJpeg: ByteArray? = null

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
        Security.insertProviderAt(Conscrypt.newProvider(), 1)

        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        etApiKey = findViewById(R.id.etApiKey)
        surfaceView = findViewById(R.id.cameraPreview)

        val prefs = getSharedPreferences("HiBuddyPrefs", Context.MODE_PRIVATE)
        val savedKey = prefs.getString("", "") ?: ""
        if (savedKey.isNotEmpty()) {
            etApiKey.setText(savedKey)
            updateStatus("Pronto (Chiave salvata)")
        } else {
            updateStatus("Inserisci e salva la tua API Key in alto")
        }

        findViewById<Button>(R.id.btnSaveKey).setOnClickListener {
            val newKey = etApiKey.text.toString().trim()
            if (newKey.isNotEmpty()) {
                prefs.edit().putString("", newKey).apply()
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
        val apiKey = prefs.getString("", "")?.trim() ?: ""
        if (apiKey.isEmpty()) {
            updateStatus("Inserisci e salva prima la API Key in alto")
            return
        }

        val host = "wss://://googleapis.com"
        val path = "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        val wsUrl = "$host$path?key=$apiKey"

        updateStatus("Connessione in corso...")
        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                isConnected = true
                updateStatus("Connesso! Parla pure...")
                sendInitialSetup(ws)
                startAudioStreaming()
                videoHandler.post(videoRunnable)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                isRecording = false
                videoHandler.removeCallbacks(videoRunnable)
                updateStatus("Errore: ${t.message}")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                isConnected = false
                isRecording = false
                videoHandler.removeCallbacks(videoRunnable)
                updateStatus("Disconnesso ($reason)")
            }
        })
    }

    private fun stopSession() {
        isConnected = false
        isRecording = false
        videoHandler.removeCallbacks(videoRunnable)
        webSocket?.close(1000, "Chiusura utente")
        webSocket = null
        updateStatus("Sessione terminata")
    }

    private fun sendInitialSetup(ws: WebSocket) {
        val setupJson = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", MODEL)
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", "Aoede")
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
            })
        }
        ws.send(setupJson.toString())
    }

    private fun startAudioStreaming() {
        isRecording = true
        thread {
            val bufferSize = AudioRecord.getMinBufferSize(
                INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return@thread
            }
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            val buffer = ByteArray(bufferSize)
            recorder.startRecording()

            while (isRecording && isConnected) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val base64Audio = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                    val msg = JSONObject().apply {
                        put("realtimeInput", JSONObject().apply {
                            put("mediaChunks", JSONArray().put(JSONObject().apply {
                                put("mimeType", "audio/pcm;rate=16000")
                                put("data", base64Audio)
                            }))
                        })
                    }
                    webSocket?.send(msg.toString())
                }
            }
            try {
                recorder.stop()
                recorder.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun handleServerMessage(jsonString: String) {
        try {
            val json = JSONObject(jsonString)
            val serverContent = json.optJSONObject("serverContent") ?: return

            if (serverContent.optBoolean("interrupted", false)) {
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
val audioBytes = Base64.decode(inlineData.getString("data"), Base64.DEFAULT)
audioTrack?.write(audioBytes, 0, audioBytes.size)
}
}
}
} catch (e: Exception) {
e.printStackTrace()
}
}
private fun sendCameraFrame() {
val jpegData = lastFrameJpeg ?: return
if (!isConnected) return
val base64Img = Base64.encodeToString(jpegData, Base64.NO_WRAP)
val msg = JSONObject().apply {
put("realtimeInput", JSONObject().apply {
put("mediaChunks", JSONArray().put(JSONObject().apply {
put("mimeType", "image/jpeg")
put("data", base64Img)
}))
})
}
webSocket?.send(msg.toString())
}
private fun setupAudioOutput() {
val minBuffer = AudioTrack.getMinBufferSize(
OUTPUT_SAMPLE_RATE,
AudioFormat.CHANNEL_OUT_MONO,
AudioFormat.ENCODING_PCM_16BIT
)
audioTrack = AudioTrack.Builder()
.setAudioAttributes(
AudioAttributes.Builder()
.setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
.build()
)
.setAudioFormat(
AudioFormat.Builder()
.setEncoding(AudioFormat.ENCODING_PCM_16BIT)
.setSampleRate(OUTPUT_SAMPLE_RATE)
.setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
.build()
)
.setBufferSizeInBytes(minBuffer)
.setTransferMode(AudioTrack.MODE_STREAM)
.build()
audioTrack?.play()
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
val parameters = cam.parameters
val size = parameters.previewSize
val yuv = YuvImage(data, ImageFormat.NV21, size.width, size.height, null)
val out = ByteArrayOutputStream()
yuv.compressToJpeg(Rect(0, 0, size.width, size.height), 60, out)
lastFrameJpeg = out.toByteArray()
}
camera?.startPreview()
} catch (e: Exception) {
updateStatus("Errore fotocamera: ${e.message}")
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
private fun updateStatus(text: String) {
runOnUiThread {
statusText.text = text
}
}
override fun onDestroy() {
super.onDestroy()
stopSession()
audioTrack?.release()
}
}
