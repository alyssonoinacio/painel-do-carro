package br.car.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Typeface
import android.provider.Settings
import java.text.Normalizer

/** Paleta do HUD. Âmbar só aparece quando o Jarvis está ouvindo ou há atualização. */
object Hud {
    const val ABISMO = 0xFF050B14.toInt()
    const val CASCO = 0xFF0B1A2A.toInt()
    const val REATOR = 0xFF3FE0FF.toInt()
    const val GELO = 0xFFCFF6FF.toInt()
    const val ACO = 0xFF5D7A92.toInt()
    const val AMBAR = 0xFFFFB547.toInt()

    private var medio: Typeface? = null
    private var forte: Typeface? = null
    fun medio(c: Context) = medio ?: Typeface.createFromAsset(c.assets, "rajdhani_medium.ttf").also { medio = it }
    fun forte(c: Context) = forte ?: Typeface.createFromAsset(c.assets, "rajdhani_semibold.ttf").also { forte = it }
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float) = v * resources.displayMetrics.density

/** Animações desligadas nas Opções do desenvolvedor = sem movimento contínuo. */
fun Context.semMovimento(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

fun String.semAcento(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase().trim()

fun Context.launchableApps(): List<ResolveInfo> {
    val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return packageManager.queryIntentActivities(i, 0)
        .filter { it.activityInfo.packageName != packageName }
        .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
}

fun Context.prefs() = getSharedPreferences("car", Context.MODE_PRIVATE)

fun Context.pins(): MutableSet<String> = prefs().getStringSet("pins", emptySet())!!.toMutableSet()
fun Context.savePins(s: Set<String>) = prefs().edit().putStringSet("pins", s).apply()

fun Context.startPkg(pkg: String): Boolean {
    val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(i)
    return true
}

fun Context.instalado(pkg: String) = packageManager.getLaunchIntentForPackage(pkg) != null

const val URL_PADRAO = "https://gestorvendasia.com.br/jarvis"
const val MODELO_PADRAO = "gemini-2.5-flash-lite"
const val VOZ_PADRAO = "Charon"
val VOZES_JARVIS = listOf("Charon", "Orus", "Algieba", "Iapetus", "Fenrir")

fun Context.srvUrl(): String = prefs().getString("srv_url", null) ?: URL_PADRAO
fun Context.srvChave(): String = prefs().getString("srv_chave", "").orEmpty()
fun Context.srvModelo(): String = prefs().getString("srv_modelo", "").orEmpty().ifBlank { MODELO_PADRAO }
fun Context.vozNome(): String = prefs().getString("voz_nome", null) ?: VOZ_PADRAO
fun Context.hotword(): Boolean = prefs().getBoolean("hotword", false)

/** Base ".../v1" a partir do que o usuário digitou (com ou sem /v1, com ou sem /chat/completions). */
fun baseV1(url: String): String {
    var b = url.trim().trimEnd('/')
    b = b.removeSuffix("/chat/completions")
    return if (b.endsWith("/v1")) b else "$b/v1"
}

val NAVEGACAO = listOf("com.waze", "com.google.android.apps.maps")
val MUSICA = listOf("com.spotify.music", "com.google.android.apps.youtube.music", "com.google.android.music")
