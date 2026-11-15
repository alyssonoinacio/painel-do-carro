package br.car.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Ouvido do Jarvis, em primeiro plano para continuar ouvindo com o Waze ou a música na frente.
 * Modo espera: reconhecimento contínuo procurando a palavra "Jarvis" (o comando pode vir na mesma frase:
 * "Jarvis, navegar para casa"). Depois de uma resposta de conversa, escuta mais uma vez sem precisar
 * dizer "Jarvis", para o diálogo seguir.
 */
class JarvisService : Service() {
    companion object {
        const val INICIAR = "iniciar"; const val OUVIR = "ouvir"; const val PARAR = "parar"
        private const val CANAL = "jarvis"
        private val PALAVRA = Regex("\\b(jarvis|jarviz|jarves|jervis|djarvis|jarbas)\\b")

        /** A tela inicial se inscreve aqui para refletir o estado no reator. */
        var ouvinte: ((Estado, String?, String?) -> Unit)? = null
        var estado = Estado.PARADO; private set
        var frase: String? = null; private set
        var fala: String? = null; private set

        fun enviar(c: Context, acao: String) {
            val i = Intent(c, JarvisService::class.java).setAction(acao)
            try { if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i) } catch (e: Exception) {}
        }
    }

    private enum class Modo { DESLIGADO, ESPERA, COMANDO, OCUPADO }

    private val h = Handler(Looper.getMainLooper())
    private lateinit var jarvis: Jarvis
    private lateinit var voz: Voz
    private var rec: SpeechRecognizer? = null
    private var modo = Modo.DESLIGADO
    private var falhas = 0
    private var silenciado = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        jarvis = Jarvis(this); voz = Voz(this)
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CANAL, "Jarvis ouvindo", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notificacao(): Notification {
        val abrir = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CANAL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis")
            .setContentText(if (hotword()) "Diga “Jarvis” para falar comigo." else "Ouvindo você.")
            .setContentIntent(abrir).setOngoing(true).build()
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, notificacao(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(1, notificacao())
        } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            publicar(Estado.PARADO, null, "Este tablet não tem reconhecimento de voz. Instale o app Google pela Play Store.")
            encerrar(); return START_NOT_STICKY
        }
        when (i?.action) {
            OUVIR -> ouvirComando()
            PARAR -> encerrar()
            else -> if (hotword()) { if (modo == Modo.DESLIGADO) esperar() } else if (modo == Modo.DESLIGADO) encerrar()
        }
        return START_STICKY
    }

    // ---------- reconhecimento ----------
    private fun recognizer(): SpeechRecognizer =
        rec ?: SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(escuta); rec = it }

    private fun pedido(espera: Boolean) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, espera) // economiza dados na escuta contínua, se houver pacote offline
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)

    private fun esperar() {
        modo = Modo.ESPERA
        silenciar(true) // o bipe de cada reinício da escuta não pode tocar a cada poucos segundos
        publicar(Estado.AGUARDANDO, null, null)
        try { recognizer().cancel(); recognizer().startListening(pedido(true)) } catch (e: Exception) { reiniciarDepois(2000) }
    }

    private fun ouvirComando() {
        voz.parar()
        modo = Modo.COMANDO
        silenciar(false) // aqui o bipe é útil: avisa que pode falar
        publicar(Estado.OUVINDO, null, null)
        try { recognizer().cancel(); recognizer().startListening(pedido(false)) } catch (e: Exception) { voltar() }
    }

    private fun reiniciarDepois(ms: Long) {
        h.removeCallbacksAndMessages(null)
        h.postDelayed({ if (modo == Modo.ESPERA) esperar() }, ms)
    }

    private val escuta = object : RecognitionListener {
        override fun onResults(b: Bundle?) {
            falhas = 0
            val textos = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            when (modo) {
                Modo.ESPERA -> {
                    val t = textos.firstOrNull { PALAVRA.containsMatchIn(it.semAcento()) }
                    if (t == null) { reiniciarDepois(150); return }
                    val resto = depoisDaPalavra(t)
                    if (resto.length < 2) acordou() else processar(resto)
                }
                Modo.COMANDO -> {
                    val t = textos.firstOrNull()
                    if (t.isNullOrBlank()) voltar() else processar(depoisDaPalavra(t).ifBlank { t })
                }
                else -> {}
            }
        }

        override fun onError(erro: Int) {
            when (modo) {
                Modo.ESPERA -> when (erro) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> reiniciarDepois(150)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        publicar(Estado.PARADO, null, "Preciso da permissão do microfone. Toque no círculo para conceder.")
                        encerrar()
                    }
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> {
                        falhas++; reiniciarDepois(minOf(60_000L, 5_000L * falhas)) // sem internet: tenta de novo, cada vez mais devagar
                    }
                    else -> { rec?.destroy(); rec = null; reiniciarDepois(1000) }
                }
                Modo.COMANDO -> voltar()
                else -> {}
            }
        }

        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(b: Bundle?) {}
        override fun onEvent(t: Int, b: Bundle?) {}
    }

    private fun depoisDaPalavra(t: String): String {
        val palavras = t.trim().split(Regex("\\s+"))
        val i = palavras.indexOfFirst { PALAVRA.containsMatchIn(it.semAcento()) }
        return if (i < 0) t.trim() else palavras.drop(i + 1).joinToString(" ").trim(' ', ',', '.', '!', '?')
    }

    // ---------- conversa ----------
    private fun acordou() {
        modo = Modo.OCUPADO
        rec?.cancel()
        publicar(Estado.FALANDO, null, "Pois não, senhor?")
        voz.falar("Pois não, senhor?", fixa = true) { ouvirComando() }
    }

    private fun processar(texto: String) {
        modo = Modo.OCUPADO
        rec?.cancel()
        publicar(Estado.PENSANDO, texto, null)
        jarvis.responder(texto) { r ->
            publicar(Estado.FALANDO, texto, r.fala)
            r.acao?.let { try { it() } catch (e: Exception) {} }
            voz.falar(r.fala) { if (r.conversa) ouvirComando() else voltar() }
        }
    }

    private fun voltar() {
        if (hotword()) esperar() else { publicar(Estado.PARADO, null, null); encerrar() }
    }

    private fun publicar(e: Estado, f: String?, fl: String?) {
        estado = e
        if (f != null || e == Estado.OUVINDO) frase = f
        if (fl != null) fala = fl
        h.post { ouvinte?.invoke(e, frase, fala) }
    }

    /** Silencia notificações e sons do sistema só durante a escuta da palavra "Jarvis". */
    private fun silenciar(sim: Boolean) {
        if (Build.VERSION.SDK_INT < 23 || sim == silenciado) return
        val a = getSystemService(AUDIO_SERVICE) as AudioManager
        val dir = if (sim) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        try {
            a.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, dir, 0)
            a.adjustStreamVolume(AudioManager.STREAM_SYSTEM, dir, 0)
            silenciado = sim
        } catch (e: Exception) {}
    }

    private fun encerrar() {
        modo = Modo.DESLIGADO
        h.removeCallbacksAndMessages(null)
        silenciar(false)
        rec?.destroy(); rec = null
        if (estado != Estado.PARADO) publicar(Estado.PARADO, null, null)
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        modo = Modo.DESLIGADO
        h.removeCallbacksAndMessages(null)
        silenciar(false)
        rec?.destroy(); rec = null
        voz.desligar()
        super.onDestroy()
    }
}
