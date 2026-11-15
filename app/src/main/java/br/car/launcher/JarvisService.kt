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
        const val INICIAR = "iniciar"; const val OUVIR = "ouvir"; const val PARAR = "parar"; const val CANCELAR = "cancelar"
        private const val CANAL = "jarvis"
        private val PALAVRA = Regex("\\b(jarvis|jarviz|jarves|jervis|djarvis|jarbas)\\b")

        /** A tela inicial se inscreve aqui para refletir o estado no reator. */
        var ouvinte: ((Estado, String?, String?) -> Unit)? = null
        var estado = Estado.PARADO; private set
        var frase: String? = null; private set
        var fala: String? = null; private set

        /** Últimos eventos da escuta, mostrados em Ajustes > Diagnóstico. */
        val diag = ArrayDeque<String>()
        @Volatile var rodando = false; private set
        fun log(m: String) = synchronized(diag) {
            diag.addFirst(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + "  " + m)
            while (diag.size > 25) diag.removeLast()
        }
        fun nomeErro(e: Int) = when (e) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "rede lenta"
            SpeechRecognizer.ERROR_NETWORK -> "sem rede"
            SpeechRecognizer.ERROR_AUDIO -> "microfone ocupado ou com falha"
            SpeechRecognizer.ERROR_SERVER -> "servidor de voz do Google"
            SpeechRecognizer.ERROR_CLIENT -> "cliente"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "silêncio"
            SpeechRecognizer.ERROR_NO_MATCH -> "não entendi"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "reconhecedor ocupado"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "sem permissão do microfone"
            12 -> "idioma não suportado"
            13 -> "idioma pt-BR indisponível"
            else -> "código $e"
        }

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
    private var ultimoAlerta = 0L

    /** Avisa por voz se o motor esquentar ou se o alternador parar de carregar (só quando o Jarvis está livre). */
    private val alertaMotor: (Leitura?) -> Unit = { l ->
        val agora = System.currentTimeMillis()
        if (l != null && modo == Modo.ESPERA && agora - ultimoAlerta > 10 * 60_000L) {
            val aviso = when {
                (l.motor ?: 0) >= 108 -> "Atenção, senhor: o motor está a ${l.motor} graus. Se continuar subindo, pare em local seguro."
                (l.rpm ?: 0) > 600 && (l.tensao ?: 14f) < 12.4f -> "Atenção, senhor: a bateria está com ${String.format(java.util.Locale("pt", "BR"), "%.1f", l.tensao)} volts com o motor ligado. O alternador pode não estar carregando."
                else -> null
            }
            if (aviso != null) { ultimoAlerta = agora; log("alerta do motor"); anunciar(aviso) }
        }
    }

    private val somCarro by lazy {
        if (Build.VERSION.SDK_INT >= 23) object : android.media.AudioDeviceCallback() {
            private var antes = SomCarro.conectado(this@JarvisService) != null
            override fun onAudioDevicesAdded(d: Array<out android.media.AudioDeviceInfo>?) {
                val agora = SomCarro.conectado(this@JarvisService) != null
                if (agora && !antes) { log("som do carro conectado"); h.postDelayed({ anunciar("Conectado ao som do carro, senhor.") }, 1500) }
                antes = agora
            }
            override fun onAudioDevicesRemoved(d: Array<out android.media.AudioDeviceInfo>?) {
                antes = SomCarro.conectado(this@JarvisService) != null
            }
        } else null
    }

    /** Fala sem ter sido chamado; só se ninguém estiver falando com o Jarvis. */
    private fun anunciar(t: String) {
        if (modo != Modo.ESPERA) return
        modo = Modo.OCUPADO
        rec?.cancel()
        publicar(Estado.FALANDO, null, t)
        voz.falar(t) { voltar() }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        rodando = true; log("serviço iniciado")
        jarvis.aviso = { t -> anunciar(t) }
        Radio.aoParar = { if (modo == Modo.DESLIGADO) encerrar() }
        Obd.ouvintes.add(alertaMotor); Obd.usar(this, "voz")
        if (Build.VERSION.SDK_INT >= 23) (getSystemService(AUDIO_SERVICE) as AudioManager)
            .registerAudioDeviceCallback(somCarro as android.media.AudioDeviceCallback, h)
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
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, notificacao(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(1, notificacao())
        } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        if (!SpeechRecognizer.isRecognitionAvailable(this) && !noAparelho()) {
            log("nenhum reconhecimento de voz instalado")
            publicar(Estado.PARADO, null, "Este tablet não tem reconhecimento de voz. Instale o app Google pela Play Store.")
            encerrar(); return START_NOT_STICKY
        }
        when (i?.action) {
            OUVIR -> ouvirComando()
            CANCELAR -> { log("escuta cancelada no toque"); rec?.cancel(); voltar() }
            PARAR -> encerrar()
            else -> if (hotword()) { if (modo == Modo.DESLIGADO) esperar() } else if (modo == Modo.DESLIGADO) encerrar()
        }
        return START_STICKY
    }

    // ---------- reconhecimento ----------
    /** Android 12+: reconhecedor que roda no próprio aparelho, usado quando não há o do Google. */
    private fun noAparelho() = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)

    private fun recognizer(): SpeechRecognizer = rec ?: (
        if (!SpeechRecognizer.isRecognitionAvailable(this) && noAparelho()) {
            log("usando o reconhecedor do próprio aparelho")
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else SpeechRecognizer.createSpeechRecognizer(this)
    ).also { it.setRecognitionListener(escuta); rec = it }

    private fun pedido(espera: Boolean) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)

    private fun esperar() {
        val vindoDeOutro = modo != Modo.ESPERA
        if (vindoDeOutro) log("esperando a palavra “Jarvis”")
        modo = Modo.ESPERA
        silenciar(true) // o bipe de cada reinício da escuta não pode tocar a cada poucos segundos
        publicar(Estado.AGUARDANDO, null, null)
        if (vindoDeOutro) { rec?.destroy(); rec = null; reiniciarDepois(300); return }
        try { recognizer().startListening(pedido(true)) } catch (e: Exception) { rec?.destroy(); rec = null; reiniciarDepois(2000) }
    }

    private var tentativasPedido = 0

    /**
     * Escuta o pedido (toque no círculo ou depois do "Jarvis"). Reinicia o MESMO reconhecedor logo após
     * cancel() dá "erro do cliente" em vários aparelhos e o círculo voltava ao repouso sem ouvir nada:
     * por isso cria um reconhecedor novo e espera um instante antes de começar.
     */
    private fun ouvirComando(primeira: Boolean = true) {
        log("ouvindo o pedido")
        if (primeira) tentativasPedido = 0
        voz.parar()
        h.removeCallbacksAndMessages(null)
        modo = Modo.COMANDO
        silenciar(false) // aqui o bipe é útil: avisa que pode falar
        publicar(Estado.OUVINDO, null, null)
        rec?.destroy(); rec = null
        h.postDelayed({
            if (modo == Modo.COMANDO) try { recognizer().startListening(pedido(false)) } catch (e: Exception) {
                log("falha ao começar a ouvir: ${e.message}"); naoOuvi("Não consegui usar o microfone. Tente de novo.")
            }
        }, 250)
    }

    private fun naoOuvi(msg: String) { publicar(Estado.PARADO, null, msg); voltar() }

    private fun reiniciarDepois(ms: Long) {
        h.removeCallbacksAndMessages(null)
        h.postDelayed({ if (modo == Modo.ESPERA) esperar() }, ms)
    }

    private val escuta = object : RecognitionListener {
        override fun onResults(b: Bundle?) {
            falhas = 0
            val textos = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            log("ouvi: “" + (textos.firstOrNull() ?: "") + "”")
            when (modo) {
                Modo.ESPERA -> {
                    val t = textos.firstOrNull { PALAVRA.containsMatchIn(it.semAcento()) }
                    if (t == null) { reiniciarDepois(150); return }
                    val resto = depoisDaPalavra(t)
                    log("palavra “Jarvis” reconhecida")
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
            if (erro != SpeechRecognizer.ERROR_NO_MATCH && erro != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) log("erro: " + nomeErro(erro))
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
                    else -> {
                        falhas++
                        if (falhas == 5) publicar(Estado.AGUARDANDO, null, "A escuta do “Jarvis” está com problema: ${nomeErro(erro)}. Veja Ajustes, Diagnóstico.")
                        rec?.destroy(); rec = null; reiniciarDepois(minOf(30_000L, 1_000L * falhas))
                    }
                }
                Modo.COMANDO -> when (erro) {
                    SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY, 11 ->
                        if (tentativasPedido++ < 2) ouvirComando(false) else naoOuvi("O reconhecimento de voz não respondeu. Tente de novo.")
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> naoOuvi("Não ouvi nada. Toque no círculo e fale logo depois do bipe.")
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { publicar(Estado.PARADO, null, "Preciso da permissão do microfone."); encerrar() }
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                        naoOuvi("Sem internet para entender a voz. Confira a conexão do tablet.")
                    SpeechRecognizer.ERROR_AUDIO -> naoOuvi("O microfone está ocupado por outro app.")
                    else -> naoOuvi("Não consegui ouvir (${nomeErro(erro)}). Tente de novo.")
                }
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
            val agir = { r.acao?.let { try { it() } catch (e: Exception) { log("falha ao executar: ${e.message}") } } }
            if (!r.agirDepois) agir()
            voz.falar(r.fala) {
                if (r.agirDepois) agir()
                if (r.conversa) ouvirComando() else voltar()
            }
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
        if (Radio.tocando) { log("rádio tocando: serviço segue em primeiro plano"); return } // a rádio precisa do serviço vivo
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        rodando = false; log("serviço parado")
        Obd.ouvintes.remove(alertaMotor); Obd.soltar("voz")
        if (Build.VERSION.SDK_INT >= 23) (getSystemService(AUDIO_SERVICE) as AudioManager)
            .unregisterAudioDeviceCallback(somCarro as android.media.AudioDeviceCallback)
        modo = Modo.DESLIGADO
        h.removeCallbacksAndMessages(null)
        silenciar(false)
        rec?.destroy(); rec = null
        voz.desligar()
        Radio.aoParar = null; Radio.parar()
        super.onDestroy()
    }
}
