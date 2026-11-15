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
    class Resposta(val fala: String, val conversa: Boolean = false, val agirDepois: Boolean = false, val acao: (() -> Unit)? = null)

    private val historico = ArrayList<JSONObject>()
    private val main = Handler(Looper.getMainLooper())

    private val br = Locale("pt", "BR")

    fun responder(frase: String, pronto: (Resposta) -> Unit) {
        if (perguntaFalhas(frase.semAcento())) { falhas(pronto); return }
        val local = comandoLocal(frase)
        if (local != null) { pronto(local); return }
        if (c.srvUrl().isBlank()) {
            pronto(Resposta("Ainda não tenho servidor para conversar. Configure em Ajustes. Comandos do carro funcionam sem ele."))
            return
        }
        thread {
            val r = try { perguntar(frase) } catch (e: Exception) {
                "Não consegui falar com o servidor. Verifique a internet ou o endereço em Ajustes."
            }
            main.post { pronto(Resposta(r, conversa = true)) }
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
        tocar(bruta)?.let { return it }

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
        carro(f)?.let { return it }
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

    // ---------- carro: som (Bluetooth do rádio) e motor (OBD2) ----------
    private fun carro(f: String): Resposta? {
        if (Regex("(conecta|conectar|parear|liga|ligar)\\b.*\\b(som|radio|bluetooth|multimidia)").containsMatchIn(f))
            return Resposta("Abri o Bluetooth. Escolha o rádio do carro na lista.") {
                c.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        if (Regex("(som|audio).*(carro|radio)|onde (esta|ta) o som").containsMatchIn(f) && !Regex("aument|diminu|abaix|maxim").containsMatchIn(f)) {
            val r = SomCarro.conectado(c)
            return Resposta(if (r != null) "O som está saindo no carro, pelo $r." else "O som está no tablet. Diga conectar no som do carro para parear.")
        }
        val obd = Obd.configurado(c)
        val pedeMotor = Regex("velocidade|quantos km|rotacao|\\bgiro\\b|\\brpm\\b|temperatura do motor|motor (esta|ta) quente|temperatura da agua|arrefecimento|combustivel|gasolina|tanque|tensao|voltagem|bateria do carro").containsMatchIn(f) ||
            (obd && Regex("\\bbateria\\b").containsMatchIn(f) && !f.contains("tablet"))
        if (!pedeMotor) return null
        if (!obd) return Resposta("Ainda não tenho o adaptador OBD2 do carro. Escolha-o em Ajustes, na seção Carro.")
        val l = Obd.leitura
        if (!Obd.conectado) return Resposta("O adaptador do carro não está conectado. Confira se a ignição está ligada.")
        return Resposta(when {
            Regex("velocidade|quantos km").containsMatchIn(f) ->
                l.velocidade?.let { "Estamos a $it quilômetros por hora." } ?: "O carro não informou a velocidade."
            Regex("rotacao|\\bgiro\\b|\\brpm\\b").containsMatchIn(f) ->
                l.rpm?.let { "O motor está a $it rotações por minuto." } ?: "O carro não informou o giro."
            Regex("combustivel|gasolina|tanque").containsMatchIn(f) ->
                l.combustivel?.let { "O tanque está com $it por cento." } ?: "O HB20 não informa o nível do tanque por este adaptador, senhor."
            Regex("tensao|voltagem|bateria").containsMatchIn(f) -> l.tensao?.let {
                val v = String.format(br, "%.1f", it)
                val leitura = when {
                    (l.rpm ?: 0) > 400 && it < 13f -> "Com o motor ligado deveria passar de 13 volts. Vale checar o alternador."
                    it >= 13.2f -> "O alternador está carregando."
                    it >= 12.2f -> "Está normal."
                    else -> "Está baixa, senhor."
                }
                "A bateria do carro está com $v volts. $leitura"
            } ?: "O adaptador não informou a tensão."
            else -> l.motor?.let {
                when {
                    it >= 105 -> "O motor está a $it graus. Acima do normal, senhor. Se subir mais, pare em local seguro."
                    it < 70 -> "O motor está a $it graus, ainda esquentando."
                    else -> "O motor está a $it graus, temperatura normal."
                }
            } ?: "O carro não informou a temperatura do motor."
        })
    }

    private fun perguntaFalhas(f: String) =
        Regex("falha|defeito|luz (da injecao|do motor)|injecao eletronica|check engine|codigo de erro|diagnostico do carro|scanner").containsMatchIn(f)

    /** Lê os códigos de falha; com servidor, pede à IA uma explicação curta de cada um. */
    private fun falhas(pronto: (Resposta) -> Unit) {
        if (!Obd.configurado(c)) { pronto(Resposta("Ainda não tenho o adaptador OBD2 do carro. Escolha-o em Ajustes, na seção Carro.")); return }
        Obd.falhas { lista ->
            when {
                lista == null -> pronto(Resposta("O adaptador do carro não está conectado. Confira se a ignição está ligada."))
                lista.isEmpty() -> pronto(Resposta("Nenhuma falha registrada na injeção, senhor."))
                else -> {
                    val codigos = lista.joinToString(", ")
                    val base = if (lista.size == 1) "Encontrei uma falha: $codigos." else "Encontrei ${lista.size} falhas: $codigos."
                    if (c.srvUrl().isBlank()) { pronto(Resposta(base)); return@falhas }
                    thread {
                        val exp = try {
                            perguntar("Explique em no máximo duas frases curtas, para o motorista, o que significa o código OBD2 " +
                                "$codigos num Hyundai HB20 2013 e se é seguro continuar dirigindo.")
                        } catch (e: Exception) { "" }
                        main.post { pronto(Resposta("$base $exp".trim(), conversa = exp.isNotBlank())) }
                    }
                }
            }
        }
    }

    /**
     * "tocar Legião Urbana no YouTube", "toca Coldplay", "coloca Anitta no Spotify".
     * Sem plataforma, vai para o YouTube. "tocar música" sem nome abre o app de música.
     */
    private fun tocar(bruta: String): Resposta? {
        val orig = bruta.trim().split(Regex("\\s+")).map { it.trim(',', '.', '!', '?') }.filter { it.isNotEmpty() }.toMutableList()
        if (orig.firstOrNull()?.semAcento() == "jarvis") orig.removeAt(0)
        val norm = orig.map { it.semAcento() }
        if (norm.isEmpty() || norm[0] !in setOf("toca", "tocar", "toque", "bota", "botar", "coloca", "colocar", "poe", "por", "play", "reproduz", "reproduzir"))
            return null
        var fimQ = norm.size
        var onde = "youtube"
        val cauda = norm.drop(1).joinToString(" ")
        when {
            Regex("\\b(no|do|pelo|na) (youtube|you tube|yt)$").containsMatchIn(cauda) ->
                fimQ -= if (cauda.endsWith("you tube")) 3 else 2
            Regex("\\b(no|do|pelo|na) (youtube music|you tube music)$").containsMatchIn(cauda) -> {
                onde = "ytmusic"; fimQ -= if (cauda.endsWith("you tube music")) 4 else 3 }
            Regex("\\b(no|do|pelo|na) spotify$").containsMatchIn(cauda) -> { onde = "spotify"; fimQ -= 2 }
        }
        val q = orig.subList(1, maxOf(1, fimQ)).joinToString(" ")
        val qn = q.semAcento()
        if (qn.isBlank() || qn in setOf("musica", "uma musica", "a musica", "um som", "som", "algo", "alguma coisa"))
            return if (onde == "youtube" && fimQ < norm.size) Resposta("Abrindo o YouTube.") { c.startPkg("com.google.android.youtube") }
                   else Resposta("Abrindo a música.") { abrirMusica() }
        return when (onde) {
            "spotify" -> Resposta("Tocando $q no Spotify.", agirDepois = true) {
                abrirUri("spotify:search:" + Uri.encode(q), "com.spotify.music") }
            "ytmusic" -> Resposta("Tocando $q no YouTube Music.", agirDepois = true) {
                abrirUri("https://music.youtube.com/search?q=" + Uri.encode(q), "com.google.android.apps.youtube.music") }
            else -> Resposta("Tocando $q no YouTube.", agirDepois = true) { youtube(q) }
        }
    }

    /** Procura no YouTube e abre o primeiro vídeo; sem achar o vídeo, abre a busca. */
    private fun youtube(q: String) {
        thread {
            val id = try {
                val con = URL("https://www.youtube.com/results?search_query=" + Uri.encode(q)).openConnection() as HttpURLConnection
                con.connectTimeout = 8000; con.readTimeout = 10000
                con.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36")
                con.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9")
                val html = con.inputStream.bufferedReader().use { it.readText() }
                Regex("\"videoId\":\"([\\w-]{11})\"").find(html)?.groupValues?.get(1)
            } catch (e: Exception) { null }
            main.post {
                if (id != null) abrirUri("https://www.youtube.com/watch?v=$id", "com.google.android.youtube")
                else abrirUri("https://www.youtube.com/results?search_query=" + Uri.encode(q), "com.google.android.youtube")
            }
        }
    }

    private fun abrirUri(uri: String, pacote: String) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (c.instalado(pacote)) i.setPackage(pacote)
        try { c.startActivity(i) } catch (e: Exception) {
            try { c.startActivity(i.setPackage(null)) } catch (e2: Exception) {}
        }
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
    private fun endpoint(base: String) = baseV1(base) + "/chat/completions"

    private fun perguntar(frase: String): String {
        historico.add(JSONObject().put("role", "user").put("content", frase))
        while (historico.size > 8) historico.removeAt(0)
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content",
            "Você é Jarvis, a inteligência artificial deste carro: educado, calmo, preciso e com um humor seco e discreto. " +
            "Trate o motorista por senhor. Responda em português do Brasil, em no máximo duas frases curtas, porque ele está " +
            "dirigindo e vai ouvir a resposta. Sem markdown, listas ou emojis. Hoje é " +
            SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy, HH:mm", br).format(Date()) + "."))
        historico.forEach { msgs.put(it) }
        val corpo = JSONObject().put("model", c.srvModelo())
            .put("messages", msgs).put("max_tokens", 300).put("stream", false)
        val texto = postar(c.srvUrl(), c.srvChave(), corpo)
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
