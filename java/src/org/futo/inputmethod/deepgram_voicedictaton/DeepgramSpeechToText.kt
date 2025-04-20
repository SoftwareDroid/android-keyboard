package org.futo.inputmethod.deepgram_voicedictaton

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.app.ActivityCompat
import org.json.JSONException
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.futo.inputmethod.latin.uix.DEEPGRAM_API_KEY
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.getSetting

class DeepgramSpeechToText {
    companion object {
        const val TAG = "WebSocketExample"
        const val SAMPLE_RATE = 16000 // Sample rate in Hz
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var context: Context? = null
    private var startTime: Long = 0
    private var client: OkHttpClient? = null

    fun startStreaming() {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (ActivityCompat.checkSelfPermission(
                context!!,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )
        audioRecord!!.startRecording()
        isRecording = true
        startTime = System.currentTimeMillis()
        Thread(AudioSender(bufferSize)).start()
    }

    fun stopStreaming() {
        if(isRecording) {
            isRecording = false
            audioRecord?.apply {
                stop()
                release()
                audioRecord = null
            }
        }
    }

    fun startWebsocket(context: Context,manager: KeyboardManagerForAction) {
        val context2 = manager.getContext()
        // Get settings from shared pref
        val apiKey = context2.getSetting(DEEPGRAM_API_KEY)
        if(apiKey.isEmpty())
        {
            Log.d(TAG, "API Key not set")
            return
        }
        this.context = context
        client = OkHttpClient()
        val language = "de"
        val url =
            "wss://api.deepgram.com/v1/listen?punctuate=true&model=nova-2&encoding=linear16&language=$language&sample_rate=$SAMPLE_RATE"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Token $apiKey")
            .build()

        webSocket = client!!.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened")
                startStreaming()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Message received: $text")
                try {
                    val obj = JSONObject(text)
                    val type = obj.getString("type")
                    if (type == "Results") {
                        val isFinal = obj.getBoolean("is_final")
                        if (isFinal) {
                            val transcript =
                                obj.getJSONObject("channel").getJSONArray("alternatives")
                                    .getJSONObject(0).getString("transcript")
                            Log.d("Transcript from WebSocket ", transcript)
                        }
                    }
                } catch (e: JSONException) {
                    throw RuntimeException(e)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d(TAG, "Message received: ${bytes.hex()}")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                Log.d(TAG, "WebSocket closing: $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}")
                stopStreaming();
            }
        })
    }

    private inner class AudioSender(private val bufferSize: Int) : Runnable {
        override fun run() {
            val audioBuffer = ByteArray(bufferSize)
            while (isRecording) {
                val read = audioRecord!!.read(audioBuffer, 0, bufferSize)
                if (read > 0) {
                    webSocket!!.send(audioBuffer.toByteString(0, read))
                }
            }
        }
    }
}