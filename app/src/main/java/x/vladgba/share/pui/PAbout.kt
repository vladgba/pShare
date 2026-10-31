// pUI: the shared "About" dialog of the p* apps. Managed by configs/android/pui (apply.py):
// do not edit in an app, change the kit and re-apply.
//
// Usage (from any Activity, e.g. a menu item or an "About" row):
//     showPAbout(PAboutInfo(repo = "pCalc", privacy = true, license = true))
package x.vladgba.share.pui

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import x.vladgba.share.R

/**
 * What the About dialog links to. Buttons without a target are hidden.
 * [repo] is the GitHub repository name under [owner]; [privacy]/[license] link to PRIVACY.md /
 * LICENSE in that repository unless [privacyUrl]/[termsUrl] give explicit pages.
 */
data class PAboutInfo(
    val repo: String,
    val owner: String = "vladgba",
    val email: String = "vladgba@gmail.com",
    val privacy: Boolean = false,
    val privacyFile: String = "PRIVACY.md",
    val license: Boolean = true,
    val privacyUrl: String? = null,
    val termsUrl: String? = null,
    val wikiUrl: String? = null,
    val labels: PAboutLabels = PAboutLabels(),
)

/** Button texts, overridable for localized apps. */
data class PAboutLabels(
    val github: String = "GitHub",
    val issues: String = "Report a problem",
    val wiki: String = "Wiki",
    val privacy: String = "Privacy policy",
    val terms: String = "Terms of use",
    val license: String = "License",
    val version: String = "Version %s",
)

fun Activity.showPAbout(info: PAboutInfo): Dialog {
    val repoUrl = "https://github.com/${info.owner}/${info.repo}"
    val l = info.labels
    val version = try {
        @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (_: Exception) { "" }

    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = pShape(pColor(R.color.p_surface), PUi.RADIUS_L, ripple = false)
        setPadding(pDpi(24), pDpi(28), pDpi(24), pDpi(20))
    }
    content.addView(ImageView(this).apply { setImageDrawable(applicationInfo.loadIcon(packageManager)) },
        LinearLayout.LayoutParams(pDpi(72), pDpi(72)))
    content.addView(pTitle(applicationInfo.loadLabel(packageManager)).apply {
        textSize = 24f; gravity = Gravity.CENTER; setPadding(0, pDpi(16), 0, 0)
    })
    if (version.isNotEmpty()) content.addView(pSecondary(l.version.format(version)).apply {
        gravity = Gravity.CENTER; setPadding(0, pDpi(4), 0, 0)
    })
    content.addView(pBody(info.email).apply {
        setTextColor(pColor(R.color.p_accent_text))
        gravity = Gravity.CENTER
        setPadding(pDpi(12), pDpi(10), pDpi(12), pDpi(10))
        background = pShape(0, PUi.RADIUS_PILL)
        isClickable = true
        setOnClickListener { pOpenUrl("mailto:${info.email}") }
    })

    val buttons = buildList {
        add(l.github to repoUrl)
        add(l.issues to "$repoUrl/issues")
        info.wikiUrl?.let { add(l.wiki to it) }
        (info.privacyUrl ?: if (info.privacy) "$repoUrl/blob/main/${info.privacyFile}" else null)?.let { add(l.privacy to it) }
        info.termsUrl?.let { add(l.terms to it) }
        if (info.license) add(l.license to "$repoUrl/blob/main/LICENSE")
    }
    buttons.chunked(2).forEachIndexed { i, pair ->
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        pair.forEach { (label, url) ->
            row.addView(pButton(label, PButton.TONAL) { pOpenUrl(url) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).pMargins(this, 4))
        }
        if (pair.size == 1) row.addView(android.view.View(this), LinearLayout.LayoutParams(0, 0, 1f))
        content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = pDpi(if (i == 0) 12 else 0) })
    }

    return Dialog(this).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(content)
        window?.setBackgroundDrawable(ColorDrawable(0))
        window?.setLayout((resources.displayMetrics.widthPixels * 0.9f).toInt().coerceAtMost(pDpi(420)),
            ViewGroup.LayoutParams.WRAP_CONTENT)
        show()
    }
}

fun Activity.pOpenUrl(url: String) {
    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { }
}
