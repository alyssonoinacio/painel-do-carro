package br.car.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Contorno com cantos chanfrados (superior esquerdo e inferior direito), como um painel de HUD. */
class Chanfro(private val corte: Float, private val traco: Float, private val borda: Int, private val fundo: Int) : Drawable() {
    private val pf = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = fundo }
    private val pb = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = traco; color = borda }
    private val path = Path()
    override fun draw(c: Canvas) {
        val b = bounds; val h = traco / 2
        val l = b.left + h; val t = b.top + h; val r = b.right - h; val bt = b.bottom - h
        path.reset()
        path.moveTo(l + corte, t); path.lineTo(r, t); path.lineTo(r, bt - corte)
        path.lineTo(r - corte, bt); path.lineTo(l, bt); path.lineTo(l, t + corte); path.close()
        c.drawPath(path, pf); c.drawPath(path, pb)
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

fun Context.fundoModulo(): Drawable = StateListDrawable().apply {
    val corte = dpf(14f); val t = dpf(1.5f)
    addState(intArrayOf(android.R.attr.state_pressed), Chanfro(corte, t, Hud.REATOR, 0x333FE0FF))
    addState(intArrayOf(android.R.attr.state_focused), Chanfro(corte, dpf(3f), Hud.GELO, 0xCC0B1A2A.toInt()))
    addState(intArrayOf(), Chanfro(corte, t, 0x993FE0FF.toInt(), 0xCC0B1A2A.toInt()))
}

enum class Icone { NAVEGAR, MUSICA, TELEFONE, APPS, AJUSTES, ATUALIZAR }

/** Ícones de traço desenhados à mão, na mesma espessura do contorno dos módulos. */
class IconeView(c: Context, var icone: Icone, var cor: Int) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat(); val x0 = (width - s) / 2; val y0 = (height - s) / 2
        fun X(f: Float) = x0 + f * s
        fun Y(f: Float) = y0 + f * s
        p.color = cor; p.strokeWidth = s * 0.07f; p.style = Paint.Style.STROKE
        path.reset()
        when (icone) {
            Icone.NAVEGAR -> {
                path.moveTo(X(.5f), Y(.1f)); path.lineTo(X(.85f), Y(.88f)); path.lineTo(X(.5f), Y(.68f))
                path.lineTo(X(.15f), Y(.88f)); path.close(); c.drawPath(path, p)
            }
            Icone.MUSICA -> {
                c.drawCircle(X(.3f), Y(.75f), s * .13f, p)
                c.drawLine(X(.43f), Y(.75f), X(.43f), Y(.15f), p)
                path.moveTo(X(.43f), Y(.15f)); path.quadTo(X(.6f), Y(.3f), X(.78f), Y(.3f)); c.drawPath(path, p)
            }
            Icone.TELEFONE -> {
                c.drawRoundRect(RectF(X(.28f), Y(.08f), X(.72f), Y(.92f)), s * .08f, s * .08f, p)
                c.drawLine(X(.44f), Y(.8f), X(.56f), Y(.8f), p)
            }
            Icone.APPS -> {
                p.style = Paint.Style.FILL
                for (i in 0..2) for (j in 0..2) c.drawCircle(X(.2f + i * .3f), Y(.2f + j * .3f), s * .07f, p)
            }
            Icone.AJUSTES -> {
                for ((i, k) in listOf(.3f, .7f, .45f).withIndex()) {
                    val y = Y(.22f + i * .28f)
                    c.drawLine(X(.1f), y, X(.9f), y, p)
                    p.style = Paint.Style.FILL; c.drawCircle(X(k), y, s * .09f, p); p.style = Paint.Style.STROKE
                }
            }
            Icone.ATUALIZAR -> {
                c.drawLine(X(.5f), Y(.1f), X(.5f), Y(.62f), p)
                path.moveTo(X(.3f), Y(.44f)); path.lineTo(X(.5f), Y(.64f)); path.lineTo(X(.7f), Y(.44f)); c.drawPath(path, p)
                path.reset(); path.moveTo(X(.12f), Y(.66f)); path.lineTo(X(.12f), Y(.88f)); path.lineTo(X(.88f), Y(.88f))
                path.lineTo(X(.88f), Y(.66f)); c.drawPath(path, p)
            }
        }
    }
}

/** Módulo da grade: ícone em cima, rótulo embaixo, contorno chanfrado. */
class Modulo(c: Context, icone: Icone, rotulo: String) : LinearLayout(c) {
    val icon = IconeView(c, icone, Hud.REATOR)
    val texto = TextView(c)
    init {
        orientation = VERTICAL; gravity = Gravity.CENTER
        background = c.fundoModulo(); isFocusable = true; isClickable = true
        setPadding(c.dp(8), c.dp(10), c.dp(8), c.dp(10))
        minimumHeight = c.dp(76)
        addView(icon, LayoutParams(c.dp(34), c.dp(34)))
        texto.text = rotulo; texto.typeface = Hud.forte(c); texto.setTextColor(Hud.GELO)
        texto.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); texto.gravity = Gravity.CENTER
        texto.maxLines = 1; texto.setPadding(0, c.dp(6), 0, 0)
        addView(texto)
        contentDescription = rotulo
    }
    fun destacar(ligado: Boolean) {
        val cor = if (ligado) Hud.AMBAR else Hud.REATOR
        icon.cor = cor; icon.invalidate()
        texto.setTextColor(if (ligado) Hud.AMBAR else Hud.GELO)
    }
}

enum class Estado { PARADO, OUVINDO, PENSANDO, FALANDO }

/**
 * O reator: botão de voz do Jarvis. Anéis concêntricos que giram em sentidos opostos;
 * a velocidade e a cor dizem o estado (ciano parado, âmbar ouvindo, giro rápido pensando,
 * núcleo pulsando falando). Com as animações do sistema desligadas, fica estático.
 */
class Reator(c: Context) : View(c) {
    var estado = Estado.PARADO
        set(v) { field = v; invalidate() }
    private val estatico = c.semMovimento()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val nucleo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private var giro = 0f
    private var ultimo = 0L

    init { isClickable = true; isFocusable = true; contentDescription = "Falar com o Jarvis" }

    override fun onDraw(c: Canvas) {
        val agora = SystemClock.uptimeMillis()
        val dt = if (ultimo == 0L) 0f else (agora - ultimo) / 1000f
        ultimo = agora
        val vel = when (estado) { Estado.PARADO -> 12f; Estado.OUVINDO -> 40f; Estado.PENSANDO -> 160f; Estado.FALANDO -> 30f }
        if (!estatico) giro = (giro + vel * dt) % 360f

        val cx = width / 2f; val cy = height / 2f
        val r = min(width, height) / 2f * 0.94f
        val cor = if (estado == Estado.OUVINDO) Hud.AMBAR else Hud.REATOR
        val foco = if (isFocused) 1f else 0f

        // anel externo: três arcos longos girando no sentido horário
        p.color = cor; p.alpha = 230; p.strokeWidth = r * 0.05f + foco * r * 0.02f
        oval.set(cx - r, cy - r, cx + r, cy + r)
        for (i in 0..2) c.drawArc(oval, giro + i * 120f, 84f, false, p)

        // anel de marcações: 36 traços girando no sentido anti-horário
        p.strokeWidth = r * 0.02f; p.alpha = 150
        val r2 = r * 0.82f
        for (i in 0 until 36) {
            val a = Math.toRadians((-giro * 0.6f + i * 10f).toDouble())
            val comp = if (i % 3 == 0) 0.09f else 0.04f
            c.drawLine(cx + cos(a).toFloat() * r2, cy + sin(a).toFloat() * r2,
                cx + cos(a).toFloat() * r2 * (1 - comp), cy + sin(a).toFloat() * r2 * (1 - comp), p)
        }

        // anel interno contínuo
        p.alpha = 110; p.strokeWidth = r * 0.012f
        c.drawCircle(cx, cy, r * 0.62f, p)

        // núcleo com brilho; pulsa enquanto fala
        val pulso = if (estado == Estado.FALANDO && !estatico) (0.06f * sin(agora / 140.0)).toFloat() else 0f
        val rn = r * (0.5f + pulso)
        nucleo.shader = RadialGradient(cx, cy, rn, intArrayOf(cor and 0x55FFFFFF, cor and 0x22FFFFFF, 0x00000000),
            floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rn, nucleo)

        // microfone no centro
        p.alpha = 255; p.strokeWidth = r * 0.045f; p.strokeCap = Paint.Cap.ROUND
        val m = r * 0.16f
        c.drawRoundRect(RectF(cx - m * 0.55f, cy - m * 1.3f, cx + m * 0.55f, cy + m * 0.3f), m, m, p)
        oval.set(cx - m, cy - m * 0.8f, cx + m, cy + m * 0.8f)
        c.drawArc(oval, 20f, 140f, false, p)
        c.drawLine(cx, cy + m * 0.8f, cx, cy + m * 1.25f, p)
        p.strokeCap = Paint.Cap.BUTT

        if (!estatico && isShown) {
            if (estado == Estado.PARADO) postInvalidateDelayed(50) else postInvalidateOnAnimation()
        }
    }

    override fun onVisibilityChanged(v: View, visibility: Int) {
        super.onVisibilityChanged(v, visibility)
        if (visibility == VISIBLE) { ultimo = 0L; invalidate() }
    }
}
