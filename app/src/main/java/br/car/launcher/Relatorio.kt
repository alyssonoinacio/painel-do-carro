package br.car.launcher

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Relatórios para o suporte (log na VPS): erros que fecham o app, erros tratados e marcos de abertura.
 * Nunca envia o que foi falado ao Jarvis (as linhas "ouvi:" do diagnóstico são omitidas).
 */
object Relatorio {
    private const val URL_RELATORIO = "https://gestorvendasia.com.br/jarvis/erro"
    private val marcosEnviados = mutableSetOf<String>()

    private fun corpo(c: Context, tipo: String, texto: String): String {
        val diag = synchronized(JarvisService.diag) { JarvisService.diag.filterNot { it.contains("ouvi:") }.take(15) }
        val versao = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName } catch (e: Exception) { "?" }
        return JSONObject()
            .put("tipo", tipo).put("versao", versao)
            .put("android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")")
            .put("aparelho", Build.MANUFACTURER + " " + Build.MODEL)
            .put("texto", texto.take(12000))
            .put("diag", diag.joinToString("\n"))
            .toString()
    }

    private fun postar(json: String) {
        val con = URL(URL_RELATORIO).openConnection() as HttpURLConnection
        con.requestMethod = "POST"; con.doOutput = true
        con.connectTimeout = 4000; con.readTimeout = 4000
        con.setRequestProperty("Content-Type", "application/json")
        con.outputStream.use { it.write(json.toByteArray()) }
        con.responseCode
        con.disconnect()
    }

    /** Envia em segundo plano. */
    fun enviar(c: Context, tipo: String, texto: String) {
        if (!c.prefs().getBoolean("enviar_erros", true)) return
        val json = corpo(c, tipo, texto)
        thread { try { postar(json) } catch (e: Exception) {} }
    }

    /** Envia e espera até [ms] — usado quando o app está fechando por erro. */
    fun enviarAgora(c: Context, tipo: String, texto: String, ms: Long = 4000) {
        if (!c.prefs().getBoolean("enviar_erros", true)) return
        val json = corpo(c, tipo, texto)
        val t = thread { try { postar(json) } catch (e: Exception) {} }
        try { t.join(ms) } catch (e: InterruptedException) {}
    }

    /** Marco de abertura ("app iniciado", "tela criada"…), uma vez por execução. */
    fun marco(c: Context, nome: String) {
        synchronized(marcosEnviados) { if (!marcosEnviados.add(nome)) return }
        enviar(c, "marco", nome)
    }

    /** Executa um trecho opcional da tela sem deixar um erro nele derrubar o app; o erro vai para o suporte. */
    inline fun seguro(c: Context, onde: String, bloco: () -> Unit) {
        try { bloco() } catch (e: Throwable) {
            JarvisService.log("erro em $onde: ${e.javaClass.simpleName}")
            enviar(c, "erro_tratado", "$onde\n" + android.util.Log.getStackTraceString(e))
        }
    }
}
