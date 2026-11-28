package com.studyontv.parent

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 轻量级纯代码 UI 主题与组件工厂（零第三方 UI 库依赖，完整适配浅色/深色系统主题）。
 */
object ParentUi {
    data class Palette(
        val isDark: Boolean,
        val bg: Int,
        val surface: Int,
        val surfaceAlt: Int,
        val border: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val textMuted: Int,
        val accent: Int,
        val primaryFg: Int,
        val accentSoft: Int,
        val accentText: Int,
        val green: Int,
        val greenSoft: Int,
        val amber: Int,
        val amberSoft: Int,
        val red: Int,
        val redSoft: Int
    )

    /** 配色与 parent-ui/src/theme.css 对齐：单色中性灰承载结构，唯一蓝色用于主确认操作。 */
    fun palette(context: Context): Palette {
        val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val dark = nightMode == Configuration.UI_MODE_NIGHT_YES
        return if (dark) {
            Palette(
                isDark = true,
                bg = Color.parseColor("#111318"),
                surface = Color.parseColor("#191D24"),
                surfaceAlt = Color.parseColor("#222834"),
                border = Color.parseColor("#2A303B"),
                textPrimary = Color.parseColor("#F3F4F6"),
                textSecondary = Color.parseColor("#CBD5E1"),
                textMuted = Color.parseColor("#94A3B8"),
                accent = Color.parseColor("#60A5FA"),
                primaryFg = Color.parseColor("#0F172A"),
                accentSoft = Color.parseColor("#172554"),
                accentText = Color.parseColor("#BFDBFE"),
                green = Color.parseColor("#34D399"),
                greenSoft = Color.parseColor("#064E3B"),
                amber = Color.parseColor("#FBBF24"),
                amberSoft = Color.parseColor("#451A03"),
                red = Color.parseColor("#F87171"),
                redSoft = Color.parseColor("#450A0A")
            )
        } else {
            Palette(
                isDark = false,
                bg = Color.parseColor("#FAFAFB"),
                surface = Color.parseColor("#FFFFFF"),
                surfaceAlt = Color.parseColor("#F4F5F7"),
                border = Color.parseColor("#E4E7EC"),
                textPrimary = Color.parseColor("#181B22"),
                textSecondary = Color.parseColor("#475467"),
                textMuted = Color.parseColor("#667085"),
                accent = Color.parseColor("#2563EB"),
                primaryFg = Color.WHITE,
                accentSoft = Color.parseColor("#EFF6FF"),
                accentText = Color.parseColor("#1D4ED8"),
                green = Color.parseColor("#059669"),
                greenSoft = Color.parseColor("#ECFDF5"),
                amber = Color.parseColor("#D97706"),
                amberSoft = Color.parseColor("#FFFBEB"),
                red = Color.parseColor("#DC2626"),
                redSoft = Color.parseColor("#FEF2F2")
            )
        }
    }

    @Suppress("DEPRECATION")
    fun applySystemBars(activity: Activity, p: Palette) {
        activity.window.statusBarColor = p.bg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val decor = activity.window.decorView
            var flags = decor.systemUiVisibility
            flags = if (p.isDark) {
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            } else {
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
            decor.systemUiVisibility = flags
        }
    }

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun roundedBg(
        context: Context,
        fillColor: Int,
        radiusDp: Int = 14,
        strokeColor: Int? = null,
        strokeWidthDp: Int = 1,
        clickable: Boolean = false
    ): Drawable {
        val shape = GradientDrawable().apply {
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(fillColor)
            if (strokeColor != null) {
                setStroke(dp(context, strokeWidthDp).coerceAtLeast(1), strokeColor)
            }
        }
        return if (clickable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val rippleColor = Color.argb(30, 128, 138, 156)
            RippleDrawable(ColorStateList.valueOf(rippleColor), shape, null)
        } else {
            shape
        }
    }

    fun card(context: Context, p: Palette, paddingDp: Int = 16): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(context, paddingDp)
            setPadding(pad, pad, pad, pad)
            background = roundedBg(context, p.surface, radiusDp = 10, strokeColor = p.border)
        }

    fun primaryButton(context: Context, p: Palette, text: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.primaryFg)
            gravity = Gravity.CENTER
            val ph = dp(context, 16)
            val pv = dp(context, 10)
            setPadding(ph, pv, ph, pv)
            background = roundedBg(context, p.accent, radiusDp = 8, clickable = true)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    fun secondaryButton(context: Context, p: Palette, text: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.textPrimary)
            gravity = Gravity.CENTER
            val ph = dp(context, 12)
            val pv = dp(context, 9)
            setPadding(ph, pv, ph, pv)
            background = roundedBg(context, p.surface, radiusDp = 8, strokeColor = p.border, clickable = true)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    fun tintedButton(
        context: Context,
        fillColor: Int,
        textColor: Int,
        text: String,
        onClick: () -> Unit
    ): TextView = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textColor)
        gravity = Gravity.CENTER
        val ph = dp(context, 12)
        val pv = dp(context, 9)
        setPadding(ph, pv, ph, pv)
        background = roundedBg(context, fillColor, radiusDp = 8, clickable = true)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    fun badge(context: Context, text: String, bgColor: Int, textColor: Int): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textColor)
            val ph = dp(context, 8)
            val pv = dp(context, 3)
            setPadding(ph, pv, ph, pv)
            background = roundedBg(context, bgColor, radiusDp = 99)
        }

    fun sectionTitle(context: Context, p: Palette, text: String): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.textMuted)
            val top = dp(context, 14)
            val bottom = dp(context, 8)
            setPadding(dp(context, 2), top, dp(context, 2), bottom)
        }

    fun spacer(context: Context, heightDp: Int = 10, widthDp: Int = 0): View =
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                if (widthDp > 0) dp(context, widthDp) else ViewGroup.LayoutParams.MATCH_PARENT,
                if (heightDp > 0) dp(context, heightDp) else 1
            )
        }

    fun copyToClipboard(context: Context, label: String, text: String, toastMsg: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
    }
}
