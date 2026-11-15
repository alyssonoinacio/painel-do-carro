package br.car.launcher

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {
    private val VOZ = 7
    private lateinit var jarvis: Jarvis
    private var tts: TextToSpeech? = null
    private var ttsPronto = false

    private lateinit var reator: Reator
    private lateinit var estadoTxt: TextView
    private lateinit var falaTxt: TextView
    private lateinit var bateria: TextView
    private lateinit var modAtualizar: Modulo
    private lateinit var doca: LinearLayout
    private var ultimaFala: CharSequence = ""

    private val batt = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val carregando = i.getIntExtra(BatteryManager.EXTRA_STATUS, 0).let {
                it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL }
            bateria.text = if (carregando) "Carregando $l%" else "Bateria $l%"
            bateria.setTextColor(if (!carregando && l <= 15) Hud.AMBAR else Hud.ACO)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Hud.ABISMO; window.navigationBarColor = Hud.ABISMO
        jarvis = Jarvis(this)
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale("pt", "BR"); ttsPronto = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { runOnUiThread { pronto() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { runOnUiThread { pronto() } }
                })
            }
        }
        montar()
    }

    override fun onConfigurationChanged(nova: Configuration) {
        super.onConfigurationChanged(nova)
        ultimaFala = falaTxt.text
        montar()
    }

    /** Monta a tela conforme a orientação: paisagem = reator à esquerda; retrato = reator em cima. */
    private fun montar() {
        val dm = resources.displayMetrics
        val paisagem = dm.widthPixels > dm.heightPixels
        val raiz = LinearLayout(this).apply {
            orientation = if (paisagem) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setBackgroundColor(Hud.ABISMO)
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }

        // coluna do Jarvis: relógio, reator, estado e última fala
        val nucleo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val topo = LinearLayout(this).apply { gravity = Gravity.BOTTOM }
        topo.addView(TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"; typeface = Hud.forte(this@MainActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (paisagem) 56f else 64f); setTextColor(Hud.GELO)
            includeFontPadding = false
        })
        val lado = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, dp(6)) }
        lado.addView(TextClock(this).apply {
            format24Hour = "EEEE, d 'de' MMMM"; format12Hour = format24Hour; typeface = Hud.medio(this@MainActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); setTextColor(Hud.ACO)
        })
        bateria = TextView(this).apply { typeface = Hud.medio(this@MainActivity); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); setTextColor(Hud.ACO) }
        lado.addView(bateria)
        topo.addView(lado)
        nucleo.addView(topo, LinearLayout.LayoutParams(-1, -2))

        reator = Reator(this).apply { setOnClickListener { ouvir() } }
        nucleo.addView(reator, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8); bottomMargin = dp(4) })

        estadoTxt = TextView(this).apply {
            typeface = Hud.forte(this@MainActivity); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(Hud.REATOR); gravity = Gravity.CENTER; text = "Toque no círculo e fale"
        }
        nucleo.addView(estadoTxt, LinearLayout.LayoutParams(-1, -2))
        falaTxt = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); setTextColor(Hud.GELO); gravity = Gravity.CENTER
            maxLines = 3; setLineSpacing(0f, 1.15f); text = ultimaFala
            setPadding(dp(8), dp(4), dp(8), 0)
        }
        nucleo.addView(falaTxt, LinearLayout.LayoutParams(-1, -2))

        // painel de módulos
        val painel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val cols = if (paisagem) 2 else 3
        val mods = listOf(
            Modulo(this, Icone.NAVEGAR, "Navegar").apply { setOnClickListener { jarvis.navegar(null) } },
            Modulo(this, Icone.MUSICA, "Música").apply { setOnClickListener { jarvis.abrirMusica() } },
            Modulo(this, Icone.TELEFONE, "Telefone").apply { setOnClickListener {
                abrir(Intent(Intent.ACTION_DIAL), "Este tablet não tem app de telefone.") } },
            Modulo(this, Icone.APPS, "Apps").apply { setOnClickListener { startActivity(Intent(this@MainActivity, AppsActivity::class.java)) } },
            Modulo(this, Icone.AJUSTES, "Ajustes").apply { setOnClickListener { startActivity(Intent(this@MainActivity, ConfigActivity::class.java)) } },
            Modulo(this, Icone.ATUALIZAR, "Atualizações").also { modAtualizar = it }.apply { setOnClickListener {
                if (Updater.versao != null) Updater.instalar(this@MainActivity)
                else Updater.verificar(this@MainActivity, true) { marcarAtualizacao() } } },
        )
        mods.chunked(cols).forEach { linha ->
            val l = LinearLayout(this)
            linha.forEachIndexed { i, m ->
                l.addView(m, LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 0) leftMargin = dp(12) })
            }
            painel.addView(l, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
        }
        doca = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        painel.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(doca) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        if (paisagem) {
            raiz.addView(nucleo, LinearLayout.LayoutParams(0, -1, 1f))
            raiz.addView(painel, LinearLayout.LayoutParams(0, -1, 1.1f).apply { leftMargin = dp(24) })
        } else {
            raiz.addView(nucleo, LinearLayout.LayoutParams(-1, 0, 1.15f))
            raiz.addView(painel, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        }
        setContentView(raiz)
        marcarAtualizacao(); montarDoca()
    }

    /** Apps fixados (segurar na lista de apps) ficam numa doca de ícones abaixo dos módulos. */
    private fun montarDoca() {
        doca.removeAllViews()
        pins().filter { instalado(it) }.sorted().forEach { pkg ->
            doca.addView(ImageView(this).apply {
                setImageDrawable(packageManager.getApplicationIcon(pkg))
                contentDescription = packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0))
                background = fundoModulo(); setPadding(dp(10), dp(10), dp(10), dp(10))
                isFocusable = true
                setOnClickListener { startPkg(pkg) }
                setOnLongClickListener {
                    savePins(pins().apply { remove(pkg) }); montarDoca()
                    Toast.makeText(this@MainActivity, "Removido da doca", Toast.LENGTH_SHORT).show(); true
                }
            }, LinearLayout.LayoutParams(dp(72), dp(72)).apply { rightMargin = dp(10) })
        }
        (doca.parent as View).visibility = if (doca.childCount == 0) View.GONE else View.VISIBLE
    }

    private fun marcarAtualizacao() {
        val v = Updater.versao
        modAtualizar.texto.text = if (v != null) "Atualizar para $v" else "Atualizações"
        modAtualizar.destacar(v != null)
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(batt, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        montarDoca()
        Updater.verificar(this, false) { marcarAtualizacao() }
    }

    override fun onPause() { super.onPause(); unregisterReceiver(batt) }
    override fun onDestroy() { tts?.shutdown(); super.onDestroy() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {} // launcher: Voltar não sai da tela inicial

    // ---------- voz ----------
    private fun ouvir() {
        tts?.stop()
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Fale com o Jarvis")
        try {
            reator.estado = Estado.OUVINDO; estadoTxt.text = "Ouvindo…"; estadoTxt.setTextColor(Hud.AMBAR)
            @Suppress("DEPRECATION") startActivityForResult(i, VOZ)
        } catch (e: ActivityNotFoundException) {
            pronto()
            falaTxt.text = "Este tablet não tem reconhecimento de voz. Instale o app Google pela Play Store."
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(req, res, data)
        if (req != VOZ) return
        val frase = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (res != RESULT_OK || frase.isNullOrBlank()) { pronto(); return }
        reator.estado = Estado.PENSANDO; estadoTxt.text = "“$frase”"; estadoTxt.setTextColor(Hud.REATOR)
        jarvis.responder(frase) { r ->
            falaTxt.text = r.fala
            falar(r.fala)
            r.acao?.let { try { it() } catch (e: Exception) { falaTxt.text = "Não consegui fazer isso neste tablet." } }
        }
    }

    private fun falar(t: String) {
        if (!ttsPronto) { pronto(); return }
        reator.estado = Estado.FALANDO
        tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    private fun pronto() {
        reator.estado = Estado.PARADO
        estadoTxt.text = "Toque no círculo e fale"; estadoTxt.setTextColor(Hud.REATOR)
    }

    private fun abrir(i: Intent, erro: String) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { Toast.makeText(this, erro, Toast.LENGTH_LONG).show() }
    }
}
