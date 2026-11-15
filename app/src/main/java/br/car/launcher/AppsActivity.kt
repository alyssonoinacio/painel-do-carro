package br.car.launcher

import android.app.Activity
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class AppsActivity : Activity() {
    private lateinit var apps: List<ResolveInfo>

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        apps = launchableApps()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        root.addView(TextView(this).apply {
            text = "Todos os apps   (toque abre · segure para fixar na tela inicial)"
            setTextColor(Color.LTGRAY); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, 0, 0, dp(8))
        })
        val grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT; columnWidth = dp(150)
            horizontalSpacing = dp(10); verticalSpacing = dp(10)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = apps.size
            override fun getItem(p: Int) = apps[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, v: View?, parent: ViewGroup): View {
                val r = apps[p]
                return cardView(r.loadLabel(packageManager).toString(), r.loadIcon(packageManager), "").apply {
                    layoutParams = AbsListView.LayoutParams(-1, dp(120))
                }
            }
        }
        grid.setOnItemClickListener { _, _, p, _ ->
            if (startPkg(apps[p].activityInfo.packageName)) finish()
        }
        grid.setOnItemLongClickListener { _, _, p, _ ->
            val pkg = apps[p].activityInfo.packageName
            val s = pins()
            val msg = if (s.add(pkg)) "Fixado na tela inicial" else { s.remove(pkg); "Removido da tela inicial" }
            savePins(s)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            true
        }
    }
}
