package br.car.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/** Ajustes do Jarvis: servidor de conversa (OpenJarvis ou compatível) e atalho para os ajustes do Android. */
class ConfigActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = prefs()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(20), dp(28), dp(28))
        }
        col.addView(texto("Ajustes do Jarvis", 34f, Hud.GELO, true))
        col.addView(texto("Os comandos do carro funcionam sem internet. Para conversar com o Jarvis, " +
            "informe um servidor compatível com a API da OpenAI, como o OpenJarvis (jarvis serve).", 17f, Hud.ACO, false)
            .apply { setPadding(0, dp(4), 0, dp(16)) })

        val url = campo(col, "Endereço do servidor", "https://seu-servidor:8000", p.getString("srv_url", ""), false)
        val chave = campo(col, "Chave de acesso (se o servidor pedir)", "", p.getString("srv_chave", ""), true)
        val modelo = campo(col, "Modelo", "qwen3:8b", p.getString("srv_modelo", ""), false)

        status = texto("", 17f, Hud.ACO, false).apply { setPadding(0, dp(12), 0, dp(4)) }

        val botoes = LinearLayout(this).apply { setPadding(0, dp(16), 0, 0) }
        fun salvar() = p.edit().putString("srv_url", url.text.toString().trim())
            .putString("srv_chave", chave.text.toString().trim()).putString("srv_modelo", modelo.text.toString().trim()).apply()
        botoes.addView(botao("Salvar") { salvar(); status.text = "Ajustes salvos."; status.setTextColor(Hud.REATOR) },
            LinearLayout.LayoutParams(0, dp(64), 1f))
        botoes.addView(botao("Testar conexão") { salvar(); testar() },
            LinearLayout.LayoutParams(0, dp(64), 1f).apply { leftMargin = dp(12) })
        col.addView(botoes)
        col.addView(status)
        col.addView(botao("Abrir ajustes do Android") {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }, LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(20) })

        setContentView(ScrollView(this).apply { setBackgroundColor(Hud.ABISMO); addView(col) })
    }

    private fun testar() {
        val p = prefs()
        val base = p.getString("srv_url", "").orEmpty()
        if (base.isBlank()) { status.text = "Informe o endereço do servidor."; status.setTextColor(Hud.AMBAR); return }
        status.text = "Testando…"; status.setTextColor(Hud.ACO)
        thread {
            val corpo = JSONObject().put("model", p.getString("srv_modelo", "").orEmpty().ifBlank { "default" })
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Responda só: ok")))
                .put("max_tokens", 20)
            val (msg, cor) = try {
                val r = Jarvis(this).postar(base, p.getString("srv_chave", "").orEmpty(), corpo)
                "Conectado. O servidor respondeu: $r" to Hud.REATOR
            } catch (e: Exception) {
                "Sem resposta do servidor (${e.message ?: e.javaClass.simpleName}). Confira o endereço, a chave e a internet." to Hud.AMBAR
            }
            runOnUiThread { status.text = msg; status.setTextColor(cor) }
        }
    }

    private fun texto(t: String, sp: Float, cor: Int, titulo: Boolean) = TextView(this).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(cor)
        if (titulo) typeface = Hud.forte(this@ConfigActivity)
    }

    private fun campo(col: LinearLayout, rotulo: String, dica: String, valor: String?, senha: Boolean): EditText {
        col.addView(texto(rotulo, 18f, Hud.GELO, true).apply { setPadding(0, dp(10), 0, dp(4)) })
        val e = EditText(this).apply {
            setText(valor); hint = dica; setTextColor(Hud.GELO); setHintTextColor(Hud.ACO)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); background = fundoModulo(); isSingleLine = true
            setPadding(dp(16), dp(14), dp(16), dp(14))
            inputType = if (senha) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
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
