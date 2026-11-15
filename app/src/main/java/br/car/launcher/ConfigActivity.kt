package br.car.launcher

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/** Ajustes do Jarvis: servidor (gemini-balance), voz, palavra de ativação e permissões. */
class ConfigActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var url: EditText
    private lateinit var chave: EditText
    private lateinit var modelo: EditText
    private lateinit var casa: EditText
    private lateinit var trabalho: EditText
    private var voz: Voz? = null
    private lateinit var diag: TextView
    private lateinit var somStatus: TextView
    private lateinit var obdStatus: TextView
    private val relogio = android.os.Handler(android.os.Looper.getMainLooper())
    private val atualizarDiag = object : Runnable {
        override fun run() {
            diag.text = diagnostico()
            val som = SomCarro.conectado(this@ConfigActivity)
            somStatus.text = if (som != null) "Som: saindo no carro ($som)" else "Som: saindo no tablet"
            somStatus.setTextColor(if (som != null) Hud.REATOR else Hud.ACO)
            val nome = prefs().getString("obd_nome", "").orEmpty()
            obdStatus.text = if (!Obd.configurado(this@ConfigActivity)) "Nenhum adaptador escolhido."
                else "$nome: " + (if (!Obd.permitido(this@ConfigActivity)) "permita o Bluetooth" else Obd.status)
            relogio.postDelayed(this, 2000)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Hud.ABISMO; window.navigationBarColor = Hud.ABISMO
        val p = prefs()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(20), dp(28), dp(28)) }
        col.addView(texto("Ajustes do Jarvis", 34f, Hud.GELO, true))
        col.addView(texto("Os comandos do carro funcionam sem internet. Conversa e voz do Jarvis usam o seu servidor.",
            17f, Hud.ACO, false).apply { setPadding(0, dp(4), 0, dp(8)) })

        // --- ativação por voz
        secao(col, "Ativação")
        col.addView(chave("Acordar ao ouvir “Jarvis”",
            "Escuta contínua com a tela em qualquer app. Usa internet do reconhecimento de voz do Google.", hotword()) { liga ->
            p.edit().putBoolean("hotword", liga).apply()
            if (liga) {
                if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
                else JarvisService.enviar(this, JarvisService.INICIAR)
            } else JarvisService.enviar(this, JarvisService.PARAR)
        })
        col.addView(botao("Permitir abrir apps por voz com outro app na frente") {
            if (Build.VERSION.SDK_INT >= 23) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }, LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(10) })

        // --- carro: som pelo Bluetooth do rádio e adaptador OBD2
        secao(col, "Carro")
        somStatus = texto("", 17f, Hud.ACO, false)
        col.addView(somStatus)
        col.addView(botao("Parear com o rádio do carro") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
            LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(8) })
        casa = campo(col, "Endereço de casa", "Rua, número, bairro, cidade", p.getString("end_casa", ""), false)
        trabalho = campo(col, "Endereço do trabalho", "Rua, número, bairro, cidade", p.getString("end_trabalho", ""), false)
        col.addView(texto("Adaptador OBD2 (ELM327): pareie primeiro no Bluetooth do tablet, com a ignição ligada. " +
            "A senha costuma ser 1234 ou 0000.", 15f, Hud.ACO, false).apply { setPadding(0, dp(14), 0, 0) })
        obdStatus = texto("", 17f, Hud.GELO, false).apply { setPadding(0, dp(6), 0, 0) }
        col.addView(obdStatus)
        col.addView(botao("Escolher adaptador OBD2") { escolherObd() },
            LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(8) })

        // --- voz
        secao(col, "Voz")
        col.addView(chave("Voz do Gemini", "Mais natural. Sem internet, usa a voz do Android.", p.getBoolean("voz_servidor", true)) {
            p.edit().putBoolean("voz_servidor", it).apply()
        })
        val chips = LinearLayout(this).apply { setPadding(0, dp(10), 0, 0) }
        fun pintar() {
            for (i in 0 until chips.childCount) (chips.getChildAt(i) as TextView).let {
                val sel = it.text == vozNome()
                it.setTextColor(if (sel) Hud.ABISMO else Hud.GELO)
                it.background = if (sel) Chanfro(dpf(10f), dpf(1.5f), Hud.REATOR, Hud.REATOR) else fundoModulo()
            }
        }
        VOZES_JARVIS.forEach { nome ->
            chips.addView(TextView(this).apply {
                text = nome; gravity = Gravity.CENTER; typeface = Hud.forte(this@ConfigActivity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f); isClickable = true; isFocusable = true
                setPadding(dp(18), 0, dp(18), 0)
                setOnClickListener { p.edit().putString("voz_nome", nome).apply(); pintar() }
            }, LinearLayout.LayoutParams(-2, dp(56)).apply { rightMargin = dp(10) })
        }
        pintar()
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        col.addView(botao("Ouvir amostra") {
            salvar()
            val v = voz ?: Voz(this).also { voz = it }
            status.text = "Gerando a voz…"; status.setTextColor(Hud.ACO)
            v.falar("Sistemas online. Às suas ordens, senhor.") { status.text = "" }
        }, LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(10) })

        // --- servidor
        secao(col, "Servidor")
        url = campo(col, "Endereço", URL_PADRAO, p.getString("srv_url", null) ?: URL_PADRAO, false)
        chave = campo(col, "Chave de acesso", "jv-xxxx-xxxx-xxxx-xxxx", p.getString("srv_chave", ""), true)
        modelo = campo(col, "Modelo", MODELO_PADRAO, p.getString("srv_modelo", ""), false)
        val botoes = LinearLayout(this).apply { setPadding(0, dp(16), 0, 0) }
        botoes.addView(botao("Salvar") { salvar(); status.text = "Ajustes salvos."; status.setTextColor(Hud.REATOR) },
            LinearLayout.LayoutParams(0, dp(64), 1f))
        botoes.addView(botao("Testar conexão") { salvar(); testar() },
            LinearLayout.LayoutParams(0, dp(64), 1f).apply { leftMargin = dp(12) })
        col.addView(botoes)
        status = texto("", 17f, Hud.ACO, false).apply { setPadding(0, dp(12), 0, dp(4)) }
        col.addView(status)

        // --- diagnóstico da escuta
        secao(col, "Diagnóstico")
        diag = texto("", 15f, Hud.GELO, false).apply { typeface = android.graphics.Typeface.MONOSPACE; setLineSpacing(0f, 1.2f) }
        col.addView(diag)

        col.addView(botao("Abrir ajustes do Android") { startActivity(Intent(Settings.ACTION_SETTINGS)) },
            LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(20) })
        setContentView(ScrollView(this).apply { setBackgroundColor(Hud.ABISMO); addView(col) })
    }

    /** Lista os aparelhos pareados para escolher o ELM327. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun escolherObd() {
        if (!Obd.permitido(this)) { requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 2); return }
        @Suppress("DEPRECATION") val ad = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        if (ad == null || !ad.isEnabled) {
            status.text = "Ligue o Bluetooth do tablet e pareie o adaptador antes."; status.setTextColor(Hud.AMBAR)
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); return
        }
        val pareados = ad.bondedDevices.sortedByDescending {
            (it.name ?: "").uppercase().let { n -> n.contains("OBD") || n.contains("ELM") || n.contains("LINK") || n.contains("V-") }
        }
        if (pareados.isEmpty()) {
            status.text = "Nenhum aparelho pareado. Pareie o adaptador OBD2 no Bluetooth do tablet."; status.setTextColor(Hud.AMBAR)
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); return
        }
        val nomes = pareados.map { "${it.name ?: "Sem nome"}  (${it.address})" }.toMutableList()
        if (Obd.configurado(this)) nomes.add("Não usar adaptador OBD2")
        android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Adaptador OBD2")
            .setItems(nomes.toTypedArray()) { _, i ->
                if (i >= pareados.size) prefs().edit().remove("obd_mac").remove("obd_nome").apply()
                else prefs().edit().putString("obd_mac", pareados[i].address).putString("obd_nome", pareados[i].name ?: "OBD2").apply()
                Obd.reiniciar(this)
            }.show()
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(req, perms, res)
        if (req == 2) { if (res.firstOrNull() == PackageManager.PERMISSION_GRANTED) escolherObd(); return }
        if (res.firstOrNull() == PackageManager.PERMISSION_GRANTED) JarvisService.enviar(this, JarvisService.INICIAR)
        else { status.text = "Sem o microfone o Jarvis não ouve. Permita para usar a ativação por voz."; status.setTextColor(Hud.AMBAR) }
    }

    override fun onResume() { super.onResume(); relogio.post(atualizarDiag) }
    override fun onPause() { super.onPause(); salvar(); relogio.removeCallbacks(atualizarDiag) }

    /** O que um print desta tela precisa mostrar para descobrir por que o "Jarvis" não responde. */
    private fun diagnostico(): String {
        val mic = Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val google = android.speech.SpeechRecognizer.isRecognitionAvailable(this)
        val local = Build.VERSION.SDK_INT >= 31 && android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        val servico = try { Settings.Secure.getString(contentResolver, "voice_recognition_service") } catch (e: Exception) { null }
        val sobrepor = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
        val linhas = mutableListOf(
            "Microfone: " + (if (mic) "permitido" else "NEGADO"),
            "Reconhecimento: " + when { google -> "disponível"; local -> "só o do aparelho"; else -> "NENHUM instalado" },
            "Serviço de voz: " + (servico?.substringBefore('/') ?: "não informado"),
            "Acordar com “Jarvis”: " + (if (hotword()) "ligado" else "desligado"),
            "Escuta rodando: " + (if (JarvisService.rodando) "sim" else "não"),
            "Abrir apps por voz: " + (if (sobrepor) "permitido" else "não permitido"),
            "Som do carro: " + (SomCarro.conectado(this) ?: "não conectado"),
            "OBD2: " + (if (Obd.configurado(this)) Obd.status else "não configurado"),
            "Versão: " + packageManager.getPackageInfo(packageName, 0).versionName,
            "", "Últimos eventos:")
        synchronized(JarvisService.diag) { linhas.addAll(JarvisService.diag.take(12)) }
        if (JarvisService.diag.isEmpty()) linhas.add("(nenhum ainda)")
        return linhas.joinToString("\n")
    }
    override fun onDestroy() { voz?.desligar(); super.onDestroy() }

    private fun salvar() = prefs().edit().putString("srv_url", url.text.toString().trim())
        .putString("srv_chave", chave.text.toString().trim()).putString("srv_modelo", modelo.text.toString().trim())
        .putString("end_casa", casa.text.toString().trim()).putString("end_trabalho", trabalho.text.toString().trim()).apply()

    private fun testar() {
        if (srvUrl().isBlank()) { status.text = "Informe o endereço do servidor."; status.setTextColor(Hud.AMBAR); return }
        status.text = "Testando…"; status.setTextColor(Hud.ACO)
        thread {
            val corpo = JSONObject().put("model", srvModelo())
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Responda só: ok")))
                .put("max_tokens", 20)
            val (msg, cor) = try {
                "Conectado. O servidor respondeu: ${Jarvis(this).postar(srvUrl(), srvChave(), corpo)}" to Hud.REATOR
            } catch (e: Exception) {
                val m = e.message.orEmpty()
                (when {
                    m.contains("401") || m.contains("403") -> "O servidor recusou a chave. Confira a chave de acesso."
                    m.contains("404") -> "Endereço sem o Jarvis. Confira o endereço."
                    else -> "Sem resposta do servidor ($m). Confira a internet e o endereço."
                }) to Hud.AMBAR
            }
            runOnUiThread { status.text = msg; status.setTextColor(cor) }
        }
    }

    private fun texto(t: String, sp: Float, cor: Int, titulo: Boolean) = TextView(this).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(cor)
        if (titulo) typeface = Hud.forte(this@ConfigActivity)
    }

    private fun secao(col: LinearLayout, t: String) =
        col.addView(texto(t, 24f, Hud.REATOR, true).apply { setPadding(0, dp(24), 0, dp(4)) })

    private fun chave(t: String, sub: String, valor: Boolean, mudou: (Boolean) -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8))
        val txt = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        txt.addView(texto(t, 19f, Hud.GELO, true))
        txt.addView(texto(sub, 15f, Hud.ACO, false))
        addView(txt, LinearLayout.LayoutParams(0, -2, 1f))
        addView(Switch(context).apply {
            isChecked = valor; minimumHeight = dp(56)
            val estados = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(estados, intArrayOf(Hud.REATOR, Hud.ACO))
            trackTintList = ColorStateList(estados, intArrayOf(0x883FE0FF.toInt(), 0x445D7A92))
            setOnCheckedChangeListener { _, v -> mudou(v) }
        })
    }

    private fun campo(col: LinearLayout, rotulo: String, dica: String, valor: String?, senha: Boolean): EditText {
        col.addView(texto(rotulo, 18f, Hud.GELO, true).apply { setPadding(0, dp(10), 0, dp(4)) })
        val e = EditText(this).apply {
            setText(valor); hint = dica; setTextColor(Hud.GELO); setHintTextColor(Hud.ACO)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); background = fundoModulo(); isSingleLine = true
            setPadding(dp(16), dp(14), dp(16), dp(14))
            inputType = if (senha) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        col.addView(e, LinearLayout.LayoutParams(-1, -2))
        return e
    }

    private fun botao(t: String, acao: () -> Unit) = TextView(this).apply {
        text = t; gravity = Gravity.CENTER; typeface = Hud.forte(this@ConfigActivity)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); setTextColor(Hud.GELO)
        background = fundoModulo(); isClickable = true; isFocusable = true
        setOnClickListener { acao() }
    }
}
