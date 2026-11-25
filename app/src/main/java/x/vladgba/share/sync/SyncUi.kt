package x.vladgba.share.sync

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import x.vladgba.share.R

/** Tiny view DSL over framework widgets (no AndroidX). */

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

/** Suite colors (pUI tokens): follow the system light/dark mode like every p* app. */
class Palette(context: Context) {
    val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val text = context.getColor(R.color.p_text)
    val muted = context.getColor(R.color.p_text2)
    val card = context.getColor(R.color.p_surface)
    val stroke = context.getColor(R.color.p_line)
    val accent = context.getColor(R.color.p_accent)
    val accentText = context.getColor(R.color.p_accent_text)
    val accentSoft = context.getColor(R.color.p_accent_soft)
    val onAccentSoft = context.getColor(R.color.p_on_accent_soft)
    val onAccent = context.getColor(R.color.p_on_accent)
    val ripple = context.getColor(R.color.p_ripple)
    val ok = context.getColor(R.color.p_success)
    val warn = context.getColor(R.color.p_warning)
    val error = context.getColor(R.color.p_danger)

    fun alpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a shl 24)
}

fun rounded(color: Int, radius: Float, strokeColor: Int? = null, strokeWidth: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    if (strokeColor != null) setStroke(strokeWidth, strokeColor)
}

fun View.lp(width: Int = ViewGroup.LayoutParams.MATCH_PARENT, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
    LinearLayout.LayoutParams(width, height, weight)

fun View.margins(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): View {
    val p = (layoutParams as? ViewGroup.MarginLayoutParams) ?: lp()
    p.setMargins(context.dp(l), context.dp(t), context.dp(r), context.dp(b))
    layoutParams = p
    return this
}

fun LinearLayout.vertical(block: LinearLayout.() -> Unit = {}): LinearLayout =
    LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; block() }.also { addView(it, lp()) }

fun LinearLayout.horizontal(block: LinearLayout.() -> Unit = {}): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        block()
    }.also { addView(it, lp()) }

fun LinearLayout.text(
    s: CharSequence,
    color: Int,
    size: Float = 15f,
    bold: Boolean = false,
    mono: Boolean = false,
    block: TextView.() -> Unit = {},
): TextView = TextView(context).apply {
    text = s
    textSize = size
    setTextColor(color)
    if (bold) setTypeface(typeface, Typeface.BOLD)
    if (mono) typeface = Typeface.MONOSPACE
    block()
}.also { addView(it, if (orientation == LinearLayout.HORIZONTAL) lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) else lp()) }

fun LinearLayout.space(heightDp: Int) {
    addView(View(context), lp(height = context.dp(heightDp)))
}

/** Pill button. Primary = filled accent, otherwise outlined. */
fun LinearLayout.button(p: Palette, label: String, primary: Boolean = false, onClick: () -> Unit): TextView {
    val ctx = context
    val radius = ctx.dp(22).toFloat()
    val bg = if (primary) rounded(p.accent, radius) else rounded(p.accentSoft, radius)
    return TextView(ctx).apply {
        text = label
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) p.onAccent else p.onAccentSoft)
        gravity = Gravity.CENTER
        minHeight = ctx.dp(44)
        setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), ctx.dp(8))
        background = RippleDrawable(ColorStateList.valueOf(p.ripple), bg, rounded(-1, radius))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }.also {
        addView(it, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (orientation == LinearLayout.HORIZONTAL) it.margins(r = 8) else it.margins(t = 8)
    }
}

fun LinearLayout.chip(p: Palette, label: String, color: Int): TextView = TextView(context).apply {
    text = label
    textSize = 12f
    setTextColor(color)
    setPadding(context.dp(8), context.dp(2), context.dp(8), context.dp(2))
    background = rounded(p.alpha(color, 0x26), context.dp(10).toFloat())
}.also {
    addView(it, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    it.margins(l = 6)
}

fun LinearLayout.card(p: Palette, onClick: (() -> Unit)? = null, block: LinearLayout.() -> Unit): LinearLayout {
    val ctx = context
    return LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val r = ctx.dp(20).toFloat()
        background = rounded(p.card, r)
        setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
        if (onClick != null) {
            foreground = RippleDrawable(ColorStateList.valueOf(p.ripple), null, rounded(-1, r))
            setOnClickListener { onClick() }
        }
        block()
    }.also {
        addView(it, lp())
        it.margins(t = 10)
    }
}

fun LinearLayout.header(p: Palette, title: String) {
    text(title.uppercase(), p.accentText, 12f) {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.08f
        setPadding(context.dp(4), context.dp(22), 0, context.dp(2))
    }
}

fun LinearLayout.field(p: Palette, label: String, value: String = "", hint: String = "", multiline: Boolean = false, number: Boolean = false): EditText {
    text(label, p.muted, 13f) { setPadding(0, context.dp(12), 0, 0) }
    return EditText(context).apply {
        setText(value)
        this.hint = hint
        textSize = 15f
        inputType = when {
            number -> InputType.TYPE_CLASS_NUMBER
            multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        if (multiline) {
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
        } else {
            maxLines = 1
        }
    }.also { addView(it, lp()) }
}

fun relativeTime(t: Long, now: Long = System.currentTimeMillis()): String {
    if (t <= 0) return "never"
    val s = (now - t) / 1000
    return when {
        s < 45 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        else -> "${s / 86_400} d ago"
    }
}
