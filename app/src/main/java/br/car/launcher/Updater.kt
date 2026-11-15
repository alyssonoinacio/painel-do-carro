package br.car.launcher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Atualização pelo GitHub: lê a última release do repositório e instala o APK novo. */
object Updater {
    private const val OWNER = "alyssonoinacio"
    private const val REPO = "painel-do-carro"
    private const val INTERVALO_MS = 6 * 3600_000L

    @Volatile var versao: String? = null
    @Volatile var url: String? = null
    @Volatile var baixando = false

    private val main = Handler(Looper.getMainLooper())
    private fun toast(c: Context, m: String) = main.post { Toast.makeText(c, m, Toast.LENGTH_LONG).show() }

    private fun atual(c: Context) = c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "0"

    private fun maisNova(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }; val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    /** Consulta a release mais recente. manual=true ignora o intervalo e avisa o resultado. */
    fun verificar(c: Context, manual: Boolean, aoTerminar: () -> Unit) {
        val prefs = c.getSharedPreferences("car", Context.MODE_PRIVATE)
        if (!manual && System.currentTimeMillis() - prefs.getLong("ultima_checagem", 0) < INTERVALO_MS) return
        thread {
            try {
                val con = URL("https://api.github.com/repos/$OWNER/$REPO/releases/latest")
                    .openConnection() as HttpURLConnection
                con.setRequestProperty("Accept", "application/vnd.github+json")
                con.connectTimeout = 8000; con.readTimeout = 8000
                if (con.responseCode != 200) {
                    if (manual) toast(c, "Não consegui consultar as atualizações (código ${con.responseCode}).")
                } else {
                    val j = JSONObject(con.inputStream.bufferedReader().readText())
                    val tag = j.getString("tag_name").removePrefix("v")
                    val assets = j.getJSONArray("assets")
                    var apk: String? = null
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.getString("name").endsWith(".apk")) apk = a.getString("browser_download_url")
                    }
                    prefs.edit().putLong("ultima_checagem", System.currentTimeMillis()).apply()
                    if (apk != null && maisNova(tag, atual(c))) { versao = tag; url = apk }
                    else { versao = null; url = null; if (manual) toast(c, "Você já está na versão mais recente (${atual(c)}).") }
                }
            } catch (e: Exception) {
                if (manual) toast(c, "Sem conexão para verificar atualizações.")
            }
            main.post(aoTerminar)
        }
    }

    /** Baixa o APK direto para uma sessão do instalador; o Android pede a confirmação do usuário. */
    fun instalar(c: Context) {
        val link = url ?: return
        if (baixando) return
        if (Build.VERSION.SDK_INT >= 26 && !c.packageManager.canRequestPackageInstalls()) {
            toast(c, "Permita instalar apps deste Painel do Carro e toque em Atualizar de novo.")
            c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        baixando = true
        toast(c, "Baixando a atualização…")
        thread {
            try {
                val pi = c.packageManager.packageInstaller
                val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
                val s = pi.openSession(id)
                val con = URL(link).openConnection() as HttpURLConnection
                con.connectTimeout = 15000; con.readTimeout = 30000
                s.openWrite("app", 0, con.contentLengthLong).use { out ->
                    con.inputStream.use { it.copyTo(out) }
                    s.fsync(out)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val cb = PendingIntent.getBroadcast(c, id, Intent(c, UpdateReceiver::class.java), flags)
                s.commit(cb.intentSender)
            } catch (e: Exception) {
                toast(c, "Falha ao baixar a atualização. Tente de novo.")
            } finally {
                baixando = false
            }
        }
    }
}

class UpdateReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmar = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirmar?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirmar != null) c.startActivity(confirmar)
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> Toast.makeText(c, "Não foi possível atualizar: " +
                (i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "erro desconhecido"), Toast.LENGTH_LONG).show()
        }
    }
}
