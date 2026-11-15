package br.car.launcher

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Cérebro do Jarvis. Comandos do carro (navegar, música, volume, ligar, abrir app, hora, bateria)
 * são decididos aqui no tablet, sem internet. O resto vai para um servidor compatível com a API
 * da OpenAI — o OpenJarvis (`jarvis serve`) expõe exatamente isso em /v1/chat/completions.
 */
class Jarvis(private val c: Context) {
    class Resposta(val fala: String, val acao: (() -> Unit)? = null)

    private val historico = ArrayList<JSONObject>()
    private val main = Handler(Looper.getMainLooper())

    private val br = Locale("pt", "BR")

    fun responder(frase: String, pronto: (Resposta) -> Unit) {
        val local = comandoLocal(frase)
        if (local != null) { pronto(local); return }
        val url = c.prefs().getString("srv_url", "").orEmpty()
        if (url.isBlank()) {
            pronto(Resposta("Ainda não tenho servidor para conversar. Configure em Ajustes. Comandos do carro funcionam sem ele."))
            return
        }
        thread {
            val r = try { perguntar(frase) } catch (e: Exception) {
                "Não consegui falar com o servidor. Verifique a internet ou o endereço em Ajustes."
            }
            main.post { pronto(Resposta(r)) }
        }
    }

    // ---------- comandos locais ----------
    private fun comandoLocal(bruta: String): Resposta? {
        val f = bruta.semAcento().removePrefix("jarvis").trim(' ', ',', '.')

        Regex("^(?:navegar|navega|ir|vamos|me leva|me leve|leva|rota|tracar rota|como chego|como chegar)\\s+(?:ate|para|pra|pro|ao|a|na|no|em)\\s+(.+)$")
            .find(f)?.let { m ->
                val destino = bruta.trim().trimEnd('.', '!', '?').split(Regex("\\s+")).takeLast(m.groupValues[1].split(" ").size).joinToString(" ")
                return Resposta("Traçando rota para $destino.") { navegar(destino) }
            }

        val audio = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Regex("\\b(volume|som)\\b").containsMatchIn(f) || f.startsWith("aumenta") || f.startsWith("abaixa")) {
            when {
                Regex("maxim|no talo|no maximo").containsMatchIn(f) -> return Resposta("Volume no máximo.") {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0) }
                Regex("aument|sobe|subir|mais alto").containsMatchIn(f) -> return Resposta("Aumentando.") {
                    repeat(2) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0) } }
                Regex("diminu|abaix|baix|mais baixo").containsMatchIn(f) -> return Resposta("Abaixando.") {
                    repeat(2) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0) } }
            }
        }
        if (Regex("^(pausa|pausar|pause|parar a musica|para a musica)").containsMatchIn(f)) return Resposta("Pausado.") { tecla(audio, KeyEvent.KEYCODE_MEDIA_PAUSE) }
        if (Regex("^(proxima|pula|pular|avanca)").containsMatchIn(f)) return Resposta("Próxima.") { tecla(audio, KeyEvent.KEYCODE_MEDIA_NEXT) }
        if (Regex("^(anterior|volta a musica|musica anterior)").containsMatchIn(f)) return Resposta("Voltando.") { tecla(audio, KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
        if (Regex("^(continua|continuar|play|despausa)").containsMatchIn(f)) return Resposta("Tocando.") { tecla(audio, KeyEvent.KEYCODE_MEDIA_PLAY) }
        if (Regex("^(toca|tocar|toque|bota|coloca|abre a|abrir a) (uma )?musica").containsMatchIn(f) || f == "musica")
            return Resposta("Abrindo a música.") { abrirMusica() }

        Regex("^(?:ligar|liga|ligue|telefonar)\\s+(?:para|pra|pro)\\s+(.+)$").find(f)?.let { m ->
            val num = m.groupValues[1].filter { it.isDigit() }
            return if (num.length >= 8) Resposta("Ligando para ${m.groupValues[1]}.") {
                c.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            else Resposta("Abri o telefone. Ainda não leio seus contatos, então diga o número ou escolha na agenda.") {
                c.startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }

        if (Regex("que horas|horas sao").containsMatchIn(f))
            return Resposta("São ${SimpleDateFormat("HH:mm", br).format(Date())}.")
        if (Regex("que dia|qual a data|data de hoje").containsMatchIn(f))
            return Resposta("Hoje é ${SimpleDateFormat("EEEE, d 'de' MMMM", br).format(Date())}.")
        if (Regex("\\bbateria\\b").containsMatchIn(f)) {
            val b = c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            return Resposta("Bateria em ${b.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)} por cento.")
        }

        Regex("^(?:abrir|abre|abra|iniciar|inicia)\\s+(?:o |a )?(.+)$").find(f)?.let { m ->
            val alvo = m.groupValues[1]
            val pm = c.packageManager
            val app = c.launchableApps().firstOrNull { it.loadLabel(pm).toString().semAcento().contains(alvo) }
                ?: c.launchableApps().firstOrNull { alvo.contains(it.loadLabel(pm).toString().semAcento()) }
            return if (app != null) Resposta("Abrindo ${app.loadLabel(pm)}.") { c.startPkg(app.activityInfo.packageName) }
            else Resposta("Não encontrei o app $alvo neste tablet.")
        }
        return null
    }

    private fun tecla(a: AudioManager, k: Int) {
        a.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, k))
        a.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, k))
    }

    fun abrirMusica() {
        if (MUSICA.any { c.startPkg(it) }) return
        try {
            c.startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {}
    }

    fun navegar(destino: String?) {
        val q = Uri.encode(destino ?: "")
        val intent = when {
            c.instalado("com.waze") && destino != null ->
                Intent(Intent.ACTION_VIEW, Uri.parse("https://waze.com/ul?q=$q&navigate=yes")).setPackage("com.waze")
            c.instalado("com.google.android.apps.maps") && destino != null ->
                Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$q")).setPackage("com.google.android.apps.maps")
            destino == null && NAVEGACAO.any { c.instalado(it) } -> {
                NAVEGACAO.first { c.startPkg(it) }; return
            }
            else -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q"))
        }
        try { c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
    }

    // ---------- servidor (OpenJarvis ou outro compatível com OpenAI) ----------
    private fun endpoint(base: String): String {
        val b = base.trim().trimEnd('/')
        if (b.endsWith("/chat/completions")) return b
        return (if (b.endsWith("/v1")) b else "$b/v1") + "/chat/completions"
    }

    private fun perguntar(frase: String): String {
        val p = c.prefs()
        historico.add(JSONObject().put("role", "user").put("content", frase))
        while (historico.size > 8) historico.removeAt(0)
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content",
            "Você é o Jarvis, o assistente de voz deste carro. Responda em português do Brasil, em no máximo duas " +
            "frases curtas, porque o motorista está dirigindo e vai ouvir a resposta. Sem markdown, listas ou emojis."))
        historico.forEach { msgs.put(it) }
        val corpo = JSONObject().put("model", p.getString("srv_modelo", "").orEmpty().ifBlank { "default" })
            .put("messages", msgs).put("max_tokens", 300).put("stream", false)
        val texto = postar(p.getString("srv_url", "")!!, p.getString("srv_chave", "").orEmpty(), corpo)
        historico.add(JSONObject().put("role", "assistant").put("content", texto))
        return texto
    }

    /** Também usado pelo "Testar conexão" dos Ajustes. */
    fun postar(base: String, chave: String, corpo: JSONObject): String {
        val con = URL(endpoint(base)).openConnection() as HttpURLConnection
        con.requestMethod = "POST"; con.doOutput = true
        con.connectTimeout = 10000; con.readTimeout = 45000
        con.setRequestProperty("Content-Type", "application/json")
        if (chave.isNotBlank()) con.setRequestProperty("Authorization", "Bearer $chave")
        con.outputStream.use { it.write(corpo.toString().toByteArray()) }
        if (con.responseCode !in 200..299) throw RuntimeException("HTTP ${con.responseCode}")
        val j = JSONObject(con.inputStream.bufferedReader().readText())
        val bruto = j.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
        return limpar(bruto)
    }

    private fun limpar(t: String) = t.replace(Regex("(?s)<think>.*?</think>"), "")
        .replace(Regex("[*_#`>]"), "").replace(Regex("\\s+"), " ").trim()
        .ifBlank { "Não tenho uma resposta para isso." }
}
