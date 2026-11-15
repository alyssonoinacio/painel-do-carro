package br.car.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast

object UpdateItem

class MainActivity : Activity() {
    private lateinit var grid: GridView
    private lateinit var battery: TextView
    private val items = ArrayList<Any>() // Tile ou String (pacote fixado)

    private val fixed = listOf(
        Tile("Navegar", "🧭", listOf("com.waze", "com.google.android.apps.maps")) {
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))
        },
        Tile("Música", "🎵", listOf("com.spotify.music", "com.google.android.apps.youtube.music", "com.google.android.music")) {
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)
        },
        Tile("Telefone", "📞", emptyList()) { Intent(Intent.ACTION_DIAL) },
        Tile("Todos os apps", "▦", emptyList()) { Intent(this, AppsActivity::class.java) },
        Tile("Configurações", "⚙", emptyList()) { Intent(Settings.ACTION_SETTINGS) },
    )

    private val batt = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val ch = i.getIntExtra(BatteryManager.EXTRA_STATUS, 0).let {
                it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL
            }
            battery.text = "${if (ch) "⚡" else "🔋"} ${l * 100 / s}%"
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 72f); setTextColor(Color.WHITE)
        })
        header.addView(TextClock(this).apply {
            format24Hour = "EEEE, d 'de' MMMM"; format12Hour = "EEEE, d 'de' MMMM"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); setTextColor(Color.LTGRAY)
            setPadding(dp(24), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        battery = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); setTextColor(Color.LTGRAY) }
        header.addView(battery)
        root.addView(header)

        grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT; columnWidth = dp(190)
            horizontalSpacing = dp(12); verticalSpacing = dp(12)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, p, _ -> open(items[p]) }
        grid.setOnItemLongClickListener { _, _, p, _ ->
            val it = items[p]
            if (it is String) {
                savePins(pins().apply { remove(it) }); refresh()
                Toast.makeText(this, "Removido da tela inicial", Toast.LENGTH_SHORT).show()
            }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(batt, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        refresh()
        Updater.verificar(this, false) { refresh() }
    }

    override fun onPause() { super.onPause(); unregisterReceiver(batt) }

    override fun onBackPressed() {} // launcher: Voltar não sai da tela inicial

    private fun refresh() {
        items.clear(); items.addAll(fixed); items.add(UpdateItem)
        items.addAll(pins().filter { packageManager.getLaunchIntentForPackage(it) != null }.sorted())
        adapter.notifyDataSetChanged()
    }

    private fun open(it: Any) {
        if (it === UpdateItem) {
            if (Updater.versao != null) Updater.instalar(this) else Updater.verificar(this, true) { refresh() }
            return
        }
        if (it is String) { startPkg(it); return }
        val t = it as Tile
        if (t.pkgs.any { p -> startPkg(p) }) return
        val i = t.fallback()
        try {
            startActivity(i!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, "Nenhum app encontrado para ${t.label}. Instale um pela Play Store.", Toast.LENGTH_LONG).show()
        }
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, v: View?, parent: ViewGroup): View {
            val it = items[p]
            val v2 = if (it === UpdateItem) {
                val v = Updater.versao
                cardView(if (v != null) "Atualizar para $v" else "Verificar atualização", null, if (v != null) "⬇" else "🔄")
            } else if (it is Tile) {
                val pkg = it.pkgs.firstOrNull { k -> packageManager.getLaunchIntentForPackage(k) != null }
                val icon = pkg?.let { k -> packageManager.getApplicationIcon(k) }
                cardView(it.label, icon, it.emoji)
            } else {
                val pkg = it as String
                val ai = packageManager.getApplicationInfo(pkg, 0)
                cardView(packageManager.getApplicationLabel(ai).toString(), packageManager.getApplicationIcon(pkg), "")
            }
            v2.layoutParams = AbsListViewParams(dp(120))
            return v2
        }
    }

    @Suppress("FunctionName")
    private fun AbsListViewParams(h: Int) = android.widget.AbsListView.LayoutParams(-1, h)
}
