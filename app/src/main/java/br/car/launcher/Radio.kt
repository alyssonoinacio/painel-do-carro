package br.car.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Rádio pela internet (o rádio original do HB20 não troca de fonte por comando do tablet).
 * Estações do diretório aberto Radio Browser, filtradas por Brasil. Responde aos botões do volante
 * (play/pausa/próxima) pela sessão de mídia, abaixa durante a fala do Jarvis e para se outro app tocar.
 */
object Radio {
    class Estacao(val nome: String, val url: String)

    private val main = Handler(Looper.getMainLooper())
    private var ctx: Context? = null
    private var player: MediaPlayer? = null
    private var sessao: MediaSession? = null
    private var lista: List<Estacao> = emptyList()
    private var indice = 0

    @Volatile var tocando = false; private set
    var atual: Estacao? = null; private set
    /** Chamado quando a rádio para (o serviço de voz se encerra se estiver ocioso). */
    var aoParar: (() -> Unit)? = null

    private val foco = AudioManager.OnAudioFocusChangeListener { f ->
        when (f) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player?.setVolume(0.25f, 0.25f)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> player?.setVolume(0.1f, 0.1f)
            AudioManager.AUDIOFOCUS_GAIN -> player?.setVolume(1f, 1f)
            AudioManager.AUDIOFOCUS_LOSS -> parar() // YouTube, Spotify etc. assumiram
        }
    }

    /** Procura a rádio pelo nome e começa a tocar. cb(nome da estação) ou cb(null) se não achou/falhou. */
    fun tocar(c: Context, nome: String, cb: (String?) -> Unit) {
        ctx = c.applicationContext
        thread {
            val achadas = try { buscar(nome) } catch (e: Exception) { emptyList() }
            main.post {
                if (achadas.isEmpty()) { cb(null); return@post }
                lista = achadas; indice = 0
                iniciar(achadas[0], cb)
                c.prefs().edit().putString("radio_ultima", nome).apply()
            }
        }
    }

    fun ultima(c: Context): String? = c.prefs().getString("radio_ultima", null)

    fun proxima(cb: (String?) -> Unit) {
        if (lista.size < 2) { cb(null); return }
        indice = (indice + 1) % lista.size
        iniciar(lista[indice], cb)
    }

    private fun buscar(nome: String): List<Estacao> {
        val q = Uri.encode(nome.replace(Regex("(?i)\\b(fm|am)\\b"), "").trim())
        val con = URL("https://de1.api.radio-browser.info/json/stations/search?name=$q&countrycode=BR" +
            "&order=clickcount&reverse=true&limit=8&hidebroken=true").openConnection() as HttpURLConnection
        con.setRequestProperty("User-Agent", "JarvisDoCarro")
        con.connectTimeout = 8000; con.readTimeout = 10000
        val j = JSONArray(con.inputStream.bufferedReader().use { it.readText() })
        return (0 until j.length()).map { j.getJSONObject(it) }
            .filter { it.optString("url_resolved").startsWith("http") }
            .map { Estacao(it.optString("name").trim(), it.optString("url_resolved")) }
            .distinctBy { it.url }
    }

    private fun iniciar(e: Estacao, cb: (String?) -> Unit, tentativa: Int = 0) {
        val c = ctx ?: return
        liberar()
        atual = e
        val audio = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        audio.requestAudioFocus(foco, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(e.url)
                setOnPreparedListener { start(); tocando = true; sessaoAtiva(true); cb(e.nome) }
                setOnErrorListener { _, _, _ ->
                    // estação fora do ar: tenta a próxima da busca, até 3 vezes
                    if (tentativa < 2 && lista.size > 1) { indice = (indice + 1) % lista.size; iniciar(lista[indice], cb, tentativa + 1) }
                    else { parar(); cb(null) }
                    true
                }
                prepareAsync()
            }
        } catch (ex: Exception) { parar(); cb(null) }
    }

    fun pausar() { player?.pause(); tocando = false; sessaoAtiva(true) }
    fun retomar() { player?.start(); tocando = player != null; sessaoAtiva(true) }

    fun parar() {
        val estava = player != null
        liberar()
        ctx?.let { (it.getSystemService(Context.AUDIO_SERVICE) as AudioManager).let { a -> @Suppress("DEPRECATION") a.abandonAudioFocus(foco) } }
        sessao?.isActive = false
        if (estava) aoParar?.invoke()
    }

    private fun liberar() { try { player?.release() } catch (e: Exception) {}; player = null; tocando = false }

    /** Sessão de mídia: os botões do volante (pelo Bluetooth do rádio) chegam aqui enquanto a rádio é o último player. */
    private fun sessaoAtiva(sim: Boolean) {
        val c = ctx ?: return
        val s = sessao ?: MediaSession(c, "radio").also { s ->
            s.setCallback(object : MediaSession.Callback() {
                override fun onPlay() = retomar()
                override fun onPause() = pausar()
                override fun onStop() = parar()
                override fun onSkipToNext() = proxima { }
            })
            sessao = s
        }
        s.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, atual?.nome ?: "Rádio")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Jarvis do Carro").build())
        s.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT)
            .setState(if (tocando) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, 0, 1f).build())
        s.isActive = sim
    }
}
