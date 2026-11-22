package net.prototux.remoteagents.voice

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import net.prototux.remoteagents.api.ApiJson
import net.prototux.remoteagents.api.GuardedDns
import net.prototux.remoteagents.api.asText
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Speech to prompt text. Both implementations call back on the main thread. */
interface VoiceInput {
    interface Listener {
        fun onListening(active: Boolean)
        fun onPartial(text: String)
        fun onResult(text: String)
        fun onError(message: String)
    }

    fun start(listener: Listener)
    /** Ends recording and delivers the result. */
    fun stop()
    fun cancel()
}

/**
 * Android's recogniser. Uses the on-device recogniser when the phone has one, so speech stays on the phone;
 * otherwise the default service, which on some phones sends audio to Google.
 */
class SystemVoiceInput(private val context: Context, private val language: String?) : VoiceInput {
    private var recognizer: SpeechRecognizer? = null

    val onDevice: Boolean get() = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override fun start(listener: VoiceInput.Listener) {
        if (!SpeechRecognizer.isRecognitionAvailable(context) && !onDevice) { listener.onError("No speech recogniser on this phone"); return }
        val r = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = listener.onListening(true)
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = listener.onListening(false)
            override fun onError(error: Int) { listener.onListening(false); listener.onError(errorText(error)); release() }
            override fun onResults(results: Bundle?) {
                listener.onListening(false)
                listener.onResult(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
                release()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(listener::onPartial)
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        language?.takeIf { it.isNotBlank() }?.let { intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
        r.startListening(intent)
    }

    override fun stop() { recognizer?.stopListening() }
    override fun cancel() { recognizer?.cancel(); release() }
    private fun release() { recognizer?.destroy(); recognizer = null }

    private fun errorText(code: Int) = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Recogniser needs the network"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Language not available offline"
        else -> "Speech recognition failed ($code)"
    }
}

/**
 * Records AAC and sends it to the owner's OpenAI-compatible server (`POST /v1/audio/transcriptions`,
 * multipart `file` + `model`). Same network rule as even-terminal servers: plain HTTP only to private or
 * VPN addresses.
 */
class ServerVoiceInput(
    private val context: Context,
    private val baseUrl: String,
    private val model: String,
    private val language: String?,
    private val apiKey: String?,
    private val scope: kotlinx.coroutines.CoroutineScope,
) : VoiceInput {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var listener: VoiceInput.Listener? = null

    override fun start(listener: VoiceInput.Listener) {
        this.listener = listener
        if (endpoint() == null) { listener.onError("Set the speech server URL in Settings"); return }
        val f = File.createTempFile("voice", ".m4a", context.cacheDir)
        file = f
        try {
            recorder = MediaRecorder(context).apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16_000)
                setAudioEncodingBitRate(48_000)
                setOutputFile(f)
                prepare()
                start()
            }
            listener.onListening(true)
        } catch (e: Exception) {
            cleanup(); listener.onError("Could not record: ${e.message}")
        }
    }

    override fun stop() {
        val l = listener ?: return
        val f = file ?: return
        runCatching { recorder?.stop() }
        recorder?.release(); recorder = null
        l.onListening(false)
        l.onPartial("Transcribing…")
        scope.launch {
            try { l.onResult(transcribe(f)) } catch (e: Exception) { l.onError("Transcription failed: ${e.message}") } finally { f.delete(); file = null }
        }
    }

    override fun cancel() { runCatching { recorder?.stop() }; cleanup(); listener?.onListening(false) }

    private fun cleanup() { recorder?.release(); recorder = null; file?.delete(); file = null }

    private fun endpoint() = baseUrl.trim().trimEnd('/').let { u ->
        if (u.isEmpty()) null else (if (u.endsWith("/audio/transcriptions")) u else if (u.endsWith("/v1")) "$u/audio/transcriptions" else "$u/v1/audio/transcriptions").toHttpUrlOrNull()
    }

    private suspend fun transcribe(f: File): String {
        val url = endpoint() ?: throw IOException("No speech server URL")
        val client = OkHttpClient.Builder()
            .dns(GuardedDns(allowPublic = url.isHttps))
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "voice.m4a", f.asRequestBody("audio/mp4".toMediaType()))
            .addFormDataPart("model", model.ifBlank { "whisper-1" })
            .apply { language?.takeIf { it.isNotBlank() }?.let { addFormDataPart("language", it.substringBefore('-')) } }
            .build()
        val request = Request.Builder().url(url).post(body)
            .apply { apiKey?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
            .build()
        val text = suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        val s = r.body.string()
                        if (r.isSuccessful) cont.resume(s) else cont.resumeWithException(IOException("HTTP ${r.code}"))
                    }
                }
            })
        }
        val json = runCatching { ApiJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
        return json?.get("text")?.asText()?.trim() ?: text.trim()
    }
}
