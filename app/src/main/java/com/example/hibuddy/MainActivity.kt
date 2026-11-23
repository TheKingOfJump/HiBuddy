package com.example.hibuddy

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Camera
import android.media.*
import android.os.Bundle
import android.util.Base64
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import org.conscrypt.Conscrypt
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.Security
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    // INSERISCI QUI LA TUA API KEY DI GOOGLE AI STUDIO
    private val API_KEY = "LA_TUA_API_KEY"
    private val MODEL = "models/gemini-2.0-flash-exp"
    private val WS_URL = "wss://://googleapis.com"

    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var isRecording = false

    private lateinit var statusText: TextView
    private lateinit var surfaceView: SurfaceView
    private var camera: Camera? = null
    private var lastFrameJpeg: ByteArray? = null

    private val INPUT_SAMPLE_RATE = 16000
    private val OUTPUT_SAMPLE_RATE = 24000
    private var audioTrack: AudioTrack? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Security.insertProviderAt(Conscrypt.newProvider(), 1)

        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        surfaceView = findViewById(R.id.cameraPreview)

        setupCamera()
        setupAudioOutput()

        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            if (!isConnected) startSession() else stopSession()
        }

        findViewById<Button>(R.id.btnSendFrame).setOnClickListener {
            sendCameraFrame()
        }
    }

    private fun startSession() {
        updateStatus("Connessione in corso...")
        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder().url(WS_URL).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                isConnected = true
                updateStatus("Connesso! Parla pure...")
                sendInitialSetup(ws)
                startAudioStreaming()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                isRecording = false
                updateStatus("Errore: ${t.message}")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                isConnected = false
                isRecording = false
                updateStatus("Disconnesso")
            }
        })
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
                        put("text", "Sei HiBuddy, un assistente personale italiano conciso, chiaro e utile. Rispondi a voce.")
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
            recorder.stop()
            recorder.release()
        }
    }

    private fun handleServerMessage(jsonString: String) {
        try {
            val json = JSONObject(jsonString)
            val serverContent = json.optJSONObject("serverContent") ?: return
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
        updateStatus("Immagine inviata all'AI")
    }

    private fun setupAudioOutput() {
        val minBuf = AudioTrack.getMinBufferSize(
            OUTPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            OUTPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf,
            AudioTrack.MODE_STREAM
        )
        audioTrack?.play()
    }

    private fun setupCamera() {
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                try {
                    camera = Camera.open()
                    camera?.setDisplayOrientation(90)
                    camera?.setPreviewDisplay(holder)
                    camera?.setPreviewCallback { data, cam ->
                        val params = cam.parameters
                        val yuv = YuvImage(data, ImageFormat.NV21, params.previewSize.width, params.previewSize.height, null)
                        val out = ByteArrayOutputStream()
                        yuv.compressToJpeg(Rect(0, 0, params.previewSize.width, params.previewSize.height), 50, out)
                        lastFrameJpeg = out.toByteArray()
                    }
                    camera?.startPreview()
                } catch (e: Exception) {
                    updateStatus("Errore camera: ${e.message}")
                }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                camera?.release()
                camera = null
            }
        })
    }

    private fun stopSession() {
        isRecording = false
        webSocket?.close(1000, "Chiusura utente")
        isConnected = false
    }

    private fun updateStatus(text: String) {
        runOnUiThread { statusText.text = text }
    }
}
