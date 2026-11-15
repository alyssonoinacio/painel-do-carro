package br.car.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class Tile(val label: String, val emoji: String, val pkgs: List<String>, val fallback: () -> Intent?)

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.launchableApps(): List<ResolveInfo> {
    val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return packageManager.queryIntentActivities(i, 0)
        .filter { it.activityInfo.packageName != packageName }
        .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
}

fun Context.pins(): MutableSet<String> =
    getSharedPreferences("car", Context.MODE_PRIVATE).getStringSet("pins", emptySet())!!.toMutableSet()

fun Context.savePins(s: Set<String>) =
    getSharedPreferences("car", Context.MODE_PRIVATE).edit().putStringSet("pins", s).apply()

fun Context.startPkg(pkg: String): Boolean {
    val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(i)
    return true
}

fun Context.cardBg(): GradientDrawable = GradientDrawable().apply {
    setColor(Color.parseColor("#1E1E22")); cornerRadius = dp(16).toFloat()
}

/** Cartão grande: ícone (ou emoji) em cima, rótulo embaixo. */
fun Context.cardView(label: String, icon: android.graphics.drawable.Drawable?, emoji: String): View =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = cardBg()
        setPadding(dp(8), dp(14), dp(8), dp(14))
        if (icon != null) addView(ImageView(context).apply {
            setImageDrawable(icon)
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
        }) else addView(TextView(context).apply {
            text = emoji; setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f); gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        })
        addView(TextView(context).apply {
            text = label; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER; maxLines = 1; setPadding(0, dp(8), 0, 0)
        })
    }
