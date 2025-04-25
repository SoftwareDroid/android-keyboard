package org.futo.inputmethod.deepgram_voicedictaton

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.compose.ui.graphics.vector.rememberVectorPainter
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
import org.futo.inputmethod.latin.uix.DialogRequestItem
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.getSetting

class DeepgramSpeechToText(private val manager: KeyboardManagerForAction) {
    companion object {
        const val TAG = "WebSocketExample"
        const val SAMPLE_RATE = 16000 // Sample rate in Hz
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var lastTranscript = "";
    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    //    private var context: Context? = null
    private var startTime: Long = 0
    private var client: OkHttpClient? = null

    fun isStreaming(): Boolean {
        return isRecording
    }

    fun sendCloseStream() {
        var obj = JSONObject("{}")
        obj.put("type", "CloseStream");
        webSocket!!.send(obj.toString())
    }

    @SuppressLint("MissingPermission")
    private fun startStreaming() {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
//        if (ActivityCompat.checkSelfPermission(
//                context!!,
//                Manifest.permission.RECORD_AUDIO
//            ) != PackageManager.PERMISSION_GRANTED
//        ) {
//            return
//        }
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
        if (isRecording) {
            isRecording = false
            audioRecord?.apply {
                stop()
                release()
                audioRecord = null
            }
            sendCloseStream()
        }
    }

    fun startWebsocket(apiKey: String) {
        if (apiKey.isEmpty()) {
            manager.requestDialog(
                "Missing Deepgram API Key",
                listOf(
                    DialogRequestItem("OK") {},
                ),
                {}
            )
            Log.d(TAG, "API Key not set")
            return
        }
        client = OkHttpClient()
        val language = "de"
        val useSmartFormat = true
        val numerals = true
        val url =
            "wss://api.deepgram.com/v1/listen?punctuate=true&numerals=$numerals&smart_format=$useSmartFormat&model=nova-2&encoding=linear16&language=$language&sample_rate=$SAMPLE_RATE"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Token $apiKey")
            .build()

        webSocket = client!!.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened")
                manager.announce("Not Connected")
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
                            var transcript =
                                obj.getJSONObject("channel").getJSONArray("alternatives")
                                    .getJSONObject(0).getString("transcript")
                            // enforce to start with whitespace
                            if (!lastTranscript.endsWith(" ")) {
                                transcript = " $transcript";
                            }
                            manager.typeText(transcript)
                            //TODO: activate action can be used to switch language (via voice command)
                            //TODO: we have to know the real text to trigger delete word, delete sentence
                            //maybe step over words
                            lastTranscript = transcript;
                            Log.d(TAG, transcript)
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
                manager.announce("Connection Failed")
                stopStreaming();
            }
        })
    }


    private inner class AudioSender(private val bufferSize: Int) : Runnable {
        private var lastSendTime: Long = 0
        private var ENERGY_THRESHOLD = 30 //voice pakets below this threshold
        private var SILENT_PAKETS_NUMBER_THRESHOLD = 5
        private var KEEP_ALIVE_SEND_PERIOD_IN_MS = 5000

        override fun run() {
            val audioBuffer = ByteArray(bufferSize)
            var isSending = true
            var silenceCounter = 0
            while (isRecording) {
                val read = audioRecord!!.read(audioBuffer, 0, bufferSize)
                if (read > 0) {
                    val energy = calculateEnergy(audioBuffer, read)
//                    Log.d(TAG, " level $energy")
                    // Only when we are below the threshold for SILENT_PAKETS_NUMBER_THRESHOLD we pause the sending
                    if (energy >= ENERGY_THRESHOLD) {
                        Log.d(TAG, "Send normal data paket")
                        webSocket!!.send(audioBuffer.toByteString(0, read))
                        lastSendTime = System.currentTimeMillis()
                        silenceCounter = 0
                        isSending = true
                    } else {
                        silenceCounter++
                        if (silenceCounter > SILENT_PAKETS_NUMBER_THRESHOLD) {
                            isSending = false
                        }
                        else
                        {
                            webSocket!!.send(audioBuffer.toByteString(0, read))
                            lastSendTime = System.currentTimeMillis()
                        }
                    }
                    // We need to send every 10s a keep alive
                    if (!isSending && System.currentTimeMillis() - lastSendTime >= KEEP_ALIVE_SEND_PERIOD_IN_MS) {
                        Log.d(TAG, "Send keep alive")
                        //Message received: {"type":"Error","variant":"SchemaError","description":"Could not deserialize last text message: unknown variant `KeepAlive `, expected one of `CloseStream`, `Configure`, `Sync`, `KeepAlive`, `Finalize` at line 1 column 22","message":"{ \"type\": \"KeepAlive \"}"}
                        var obj = JSONObject("{}")
                        obj.put("type", "KeepAlive");
                        webSocket!!.send(obj.toString())
                        lastSendTime = System.currentTimeMillis()
                    }
                }
            }
        }

        private fun calculateEnergy(buffer: ByteArray, size: Int): Double {
            var energy = 0.0
            for (i in 0 until size) {
                energy += (buffer[i].toDouble() * buffer[i].toDouble())
            }
            return energy / size
        }
    }


}