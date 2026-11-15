package br.car.launcher

import android.app.Activity
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class AppsActivity : Activity() {
    private lateinit var apps: List<ResolveInfo>

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Hud.ABISMO; window.navigationBarColor = Hud.ABISMO
        apps = launchableApps()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Hud.ABISMO)
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        root.addView(TextView(this).apply {
            text = "Apps"; typeface = Hud.forte(this@AppsActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f); setTextColor(Hud.GELO)
        })
        root.addView(TextView(this).apply {
            text = "Toque para abrir. Segure para fixar ou tirar da doca da tela inicial."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTextColor(Hud.ACO); setPadding(0, 0, 0, dp(12))
        })
        val fixados = pins()
        val grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT; columnWidth = dp(132)
            horizontalSpacing = dp(10); verticalSpacing = dp(10)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH; selector = ColorDrawable(Color.TRANSPARENT)
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = apps.size
            override fun getItem(p: Int) = apps[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, v: View?, parent: ViewGroup): View {
                val r = apps[p]
                val fix = r.activityInfo.packageName in fixados
                return LinearLayout(this@AppsActivity).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    background = fundoModulo(); setPadding(dp(6), dp(12), dp(6), dp(10))
                    layoutParams = AbsListView.LayoutParams(-1, dp(118))
                    addView(ImageView(context).apply { setImageDrawable(r.loadIcon(packageManager)) },
                        LinearLayout.LayoutParams(dp(52), dp(52)))
                    addView(TextView(context).apply {
                        text = r.loadLabel(packageManager); typeface = Hud.medio(this@AppsActivity)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); setTextColor(if (fix) Hud.REATOR else Hud.GELO)
                        gravity = Gravity.CENTER; maxLines = 1; setPadding(0, dp(6), 0, 0)
                    })
                }
            }
        }
        grid.setOnItemClickListener { _, _, p, _ -> if (startPkg(apps[p].activityInfo.packageName)) finish() }
        grid.setOnItemLongClickListener { _, _, p, _ ->
            val pkg = apps[p].activityInfo.packageName
            val msg = if (fixados.add(pkg)) "Fixado na doca" else { fixados.remove(pkg); "Removido da doca" }
            savePins(fixados); (grid.adapter as BaseAdapter).notifyDataSetChanged()
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); true
        }
    }
}
