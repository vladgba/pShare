// pUI: shared look for p* apps that build views in code. Managed by configs/android/pui (apply.py):
// do not edit in an app, change the kit and re-apply. Colors/dimens come from the p_* resources, so
// light/dark switching and the palette stay identical to XML layouts that use the P.* styles.
package x.vladgba.share.pui

import x.vladgba.share.R
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object PUi {
    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)

    /** Radii in dp, matching p_radius_* (pill is clamped by the view height). */
    const val RADIUS_S = 8f
    const val RADIUS_M = 14f
    const val RADIUS_L = 20f
    const val RADIUS_PILL = 999f
}

fun Context.pDp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
fun Context.pDpi(v: Int): Int = pDp(v.toFloat()).toInt()
fun Context.pColor(id: Int): Int = getColor(id)

/** Filled rounded shape with the suite ripple; [stroke] draws a 1dp outline. */
fun Context.pShape(fill: Int, radiusDp: Float = PUi.RADIUS_M, stroke: Int? = null, ripple: Boolean = true): Drawable {
    val shape = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = pDp(radiusDp)
        if (stroke != null) setStroke(pDpi(1), stroke)
    }
    if (!ripple) return shape
    val mask = GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius = pDp(radiusDp) }
    return RippleDrawable(ColorStateList.valueOf(pColor(R.color.p_ripple)), shape, mask)
}

// ---------- text ----------

private fun Context.pText(text: CharSequence, sizeSp: Float, color: Int, face: Typeface? = null) = TextView(this).apply {
    this.text = text
    textSize = sizeSp
    setTextColor(pColor(color))
    if (face != null) typeface = face
}

fun Context.pTitle(text: CharSequence) = pText(text, 22f, R.color.p_text, PUi.MEDIUM)
fun Context.pSubtitle(text: CharSequence) = pText(text, 17f, R.color.p_text, PUi.MEDIUM)
fun Context.pBody(text: CharSequence) = pText(text, 15f, R.color.p_text)
fun Context.pSecondary(text: CharSequence) = pText(text, 15f, R.color.p_text2)
fun Context.pCaption(text: CharSequence) = pText(text, 12f, R.color.p_text2)

/** Upper-case accent header above a group of rows. */
fun Context.pSection(text: CharSequence) = pText(text, 12f, R.color.p_accent_text, PUi.MEDIUM).apply {
    isAllCaps = true
    letterSpacing = 0.08f
    setPadding(pDpi(4), pDpi(16), pDpi(4), pDpi(8))
}

// ---------- buttons ----------

enum class PButton(val style: Int) {
    FILLED(R.style.P_Button), TONAL(R.style.P_Button_Tonal), NEUTRAL(R.style.P_Button_Neutral),
    OUTLINE(R.style.P_Button_Outline), TEXT(R.style.P_Button_Text), DANGER(R.style.P_Button_Danger)
}

fun Context.pButton(text: CharSequence, kind: PButton = PButton.TONAL, onClick: (View) -> Unit): Button =
    Button(this, null, 0, kind.style).apply {
        this.text = text
        setOnClickListener(onClick)
    }

/** Square-ish key / tile (calculators, keyboards, pads). */
fun Context.pKey(text: CharSequence, fill: Int = R.color.p_surface2, fg: Int = R.color.p_text,
                 sizeSp: Float = 22f, radiusDp: Float = PUi.RADIUS_M, medium: Boolean = false) = TextView(this).apply {
    this.text = text
    gravity = Gravity.CENTER
    textSize = sizeSp
    setTextColor(pColor(fg))
    if (medium) typeface = PUi.MEDIUM
    background = pShape(pColor(fill), radiusDp)
    isClickable = true
    contentDescription = text
}

/** Pill chip; [selected] uses the accent-soft container. */
fun Context.pChip(text: CharSequence, selected: Boolean = false) = TextView(this).apply {
    this.text = text
    gravity = Gravity.CENTER
    textSize = 14f
    minHeight = pDpi(36)
    setPadding(pDpi(14), 0, pDpi(14), 0)
    pSetChipSelected(selected)
    isClickable = true
}

fun TextView.pSetChipSelected(selected: Boolean) {
    val c = context
    setTextColor(c.pColor(if (selected) R.color.p_on_accent_soft else R.color.p_text2))
    typeface = if (selected) PUi.MEDIUM else Typeface.DEFAULT
    background = c.pShape(c.pColor(if (selected) R.color.p_accent_soft else R.color.p_surface2), PUi.RADIUS_PILL)
}

// ---------- containers ----------

/** Vertical card on p_surface with 20dp corners and 16dp padding. */
fun Context.pCard(paddingDp: Int = 16) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = pShape(pColor(R.color.p_surface), PUi.RADIUS_L, ripple = false)
    setPadding(pDpi(paddingDp), pDpi(paddingDp), pDpi(paddingDp), pDpi(paddingDp))
}

/** Horizontal tappable row: min 56dp, ripple, 16dp start padding. */
fun Context.pRow() = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    minimumHeight = pDpi(56)
    setPadding(pDpi(16), pDpi(8), pDpi(8), pDpi(8))
    background = pShape(0, PUi.RADIUS_M)
    isClickable = true
}

fun Context.pDivider() = View(this).apply {
    setBackgroundColor(pColor(R.color.p_line))
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, pDpi(1))
}

/** Screen top bar: title (22sp medium) with optional trailing actions; 56dp tall. */
fun Context.pTopBar(title: CharSequence, vararg actions: View) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    minimumHeight = pDpi(56)
    setPadding(pDpi(16), 0, pDpi(8), 0)
    addView(pTitle(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    actions.forEach { addView(it) }
}

fun LinearLayout.LayoutParams.pMargins(ctx: Context, all: Int): LinearLayout.LayoutParams =
    apply { val m = ctx.pDpi(all); setMargins(m, m, m, m) }

// ---------- window ----------

/**
 * Pads this view by the system bar (+ IME) insets: targetSdk 35 forces edge-to-edge on Android 15+.
 * [top] = false for screens whose action bar already sits below the status bar.
 */
fun View.pPadForSystemBars(top: Boolean = true) {
    val base = intArrayOf(paddingLeft, paddingTop, paddingRight, paddingBottom)
    setOnApplyWindowInsetsListener { v, insets ->
        val (l, t, r, b) = if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            listOf(i.left, i.top, i.right, maxOf(i.bottom, ime.bottom))
        } else {
            @Suppress("DEPRECATION")
            listOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }
        v.setPadding(base[0] + l, base[1] + if (top) t else 0, base[2] + r, base[3] + b)
        insets
    }
    requestApplyInsets()
}
