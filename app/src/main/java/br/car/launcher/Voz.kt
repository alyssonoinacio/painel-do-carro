package br.car.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Voz do Jarvis. Usa a voz do Gemini pelo servidor (/v1/audio/speech do gemini-balance), com tom calmo e
 * formal de mordomo; sem servidor ou sem internet, cai na voz do próprio Android, mais grave.
 * Enquanto fala, abaixa a música (foco de áudio "duck").
 */
class Voz(private val c: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val audio = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = null
    private var ttsOk = false
    private var player: MediaPlayer? = null
    private var fim: (() -> Unit)? = null
    private var vez = 0

    private val foco = AudioManager.OnAudioFocusChangeListener { }

    init {
        tts = TextToSpeech(c) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale("pt", "BR"); tts?.setPitch(0.85f); ttsOk = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { main.post { terminar() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { main.post { terminar() } }
                })
            }
        }
    }

    /** Fala o texto e chama [depois] ao terminar. [fixa] = frase repetida (ex.: "Pois não?"), fica em cache. */
    fun falar(texto: String, fixa: Boolean = false, depois: () -> Unit) {
        parar()
        fim = depois
        val minha = ++vez
        @Suppress("DEPRECATION")
        audio.requestAudioFocus(foco, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        val servidor = c.prefs().getBoolean("voz_servidor", true) && c.srvUrl().isNotBlank()
        if (!servidor) { local(texto); return }
        thread {
            val arq = try { baixar(texto, fixa) } catch (e: Exception) { null }
            main.post {
                if (minha != vez) return@post // outra fala começou ou foi cancelada
                if (arq != null) tocar(arq, texto) else local(texto)
            }
        }
    }

    private fun baixar(texto: String, fixa: Boolean): File {
        val voz = c.vozNome()
        val arq = if (fixa) File(c.cacheDir, "fala_${voz}_${texto.hashCode()}.wav") else File(c.cacheDir, "fala.wav")
        if (fixa && arq.length() > 1000) return arq
        val con = URL(baseV1(c.srvUrl()) + "/audio/speech").openConnection() as HttpURLConnection
        con.requestMethod = "POST"; con.doOutput = true
        con.connectTimeout = 8000; con.readTimeout = 20000
        con.setRequestProperty("Content-Type", "application/json")
        if (c.srvChave().isNotBlank()) con.setRequestProperty("Authorization", "Bearer ${c.srvChave()}")
        val corpo = JSONObject().put("model", "gemini-2.5-flash-preview-tts").put("voice", voz)
            .put("input", "In a calm, deep and formal tone, like a refined AI butler, say in Brazilian Portuguese: $texto")
        con.outputStream.use { it.write(corpo.toString().toByteArray()) }
        if (con.responseCode != 200) throw RuntimeException("HTTP ${con.responseCode}")
        val bytes = con.inputStream.use { it.readBytes() }
        if (bytes.size < 1000 || String(bytes, 0, 4) != "RIFF") throw RuntimeException("áudio inválido")
        val tmp = File(arq.path + ".tmp"); tmp.writeBytes(bytes); tmp.renameTo(arq)
        return arq
    }

    private fun tocar(arq: File, texto: String) {
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(arq.path)
                setOnCompletionListener { terminar() }
                setOnErrorListener { _, _, _ -> terminar(); true }
                prepare(); start()
            }
        } catch (e: Exception) { player?.release(); player = null; local(texto) }
    }

    private fun local(texto: String) {
        if (ttsOk) tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "jarvis") else terminar()
    }

    private fun terminar() {
        player?.release(); player = null
        @Suppress("DEPRECATION") audio.abandonAudioFocus(foco)
        val f = fim; fim = null
        f?.invoke()
    }

    /** Interrompe a fala sem chamar o "depois". */
    fun parar() {
        vez++; fim = null
        tts?.stop(); player?.release(); player = null
        @Suppress("DEPRECATION") audio.abandonAudioFocus(foco)
    }

    fun desligar() { parar(); tts?.shutdown() }
}
