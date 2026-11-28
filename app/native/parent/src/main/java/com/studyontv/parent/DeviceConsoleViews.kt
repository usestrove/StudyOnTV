package com.studyontv.parent

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * 家长管理后台容器页视图构建器，包含顶部工具栏、加载进度条、WebView 与断线提示遮罩。
 */
internal class DeviceConsoleViews(
    private val activity: Activity,
    private val p: ParentUi.Palette,
    private val deviceName: String,
    private val targetHost: LanNet.ParsedHost,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onBack()
        fun onReload()
        fun onCopyUrl()
        fun onReconnect()
        fun onOpenAdbFix()
    }

    val rootView: View
    lateinit var webView: WebView
        private set
    private lateinit var progressBar: ProgressBar
    private lateinit var offlineOverlay: LinearLayout
    private lateinit var offlineDescView: TextView

    init {
        rootView = buildRoot()
    }

    private fun dp(value: Int): Int = ParentUi.dp(activity, value)

    private fun buildRoot(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
        }
        root.addView(buildTopBar())
        root.addView(View(activity).apply {
            setBackgroundColor(p.border)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        })
        progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3))
        }
        root.addView(progressBar)

        val frame = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        webView = WebView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        frame.addView(webView)
        offlineOverlay = buildOfflineOverlay()
        frame.addView(offlineOverlay)
        root.addView(frame)
        return root
    }

    private fun buildTopBar(): LinearLayout {
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(p.surface)
            val pad = dp(8)
            setPadding(pad, pad, pad, pad)
        }
        topBar.addView(TextView(activity).apply {
            text = "←"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.textPrimary)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            isClickable = true
            isFocusable = true
            setOnClickListener { callbacks.onBack() }
        })
        val titleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(4), 0, dp(6), 0)
        }
        titleCol.addView(TextView(activity).apply {
            text = deviceName
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.textPrimary)
            isSingleLine = true
        })
        titleCol.addView(TextView(activity).apply {
            text = targetHost.host
            textSize = 11.5f
            setTextColor(p.textMuted)
            isSingleLine = true
        })
        topBar.addView(titleCol)
        topBar.addView(ParentUi.secondaryButton(activity, p, "刷新") { callbacks.onReload() })
        topBar.addView(ParentUi.spacer(activity, 1, 6))
        topBar.addView(ParentUi.secondaryButton(activity, p, "复制网址") { callbacks.onCopyUrl() })
        return topBar
    }

    private fun buildOfflineOverlay(): LinearLayout {
        val overlay = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(p.bg)
            visibility = View.GONE
            val pad = dp(28)
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        overlay.addView(TextView(activity).apply {
            text = "连不上「$deviceName」"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.textPrimary)
            gravity = Gravity.CENTER
        })
        offlineDescView = TextView(activity).apply {
            text = "请确认设备已打开「题了个题」且在同一 Wi-Fi"
            textSize = 13.5f
            setTextColor(p.textSecondary)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(18))
        }
        overlay.addView(offlineDescView)
        overlay.addView(ParentUi.primaryButton(activity, p, "重试") { callbacks.onReconnect() })
        overlay.addView(ParentUi.spacer(activity, 10))
        overlay.addView(ParentUi.tintedButton(activity, p.accentSoft, p.accentText, "无线 ADB 修复") {
            callbacks.onOpenAdbFix()
        })
        overlay.addView(ParentUi.spacer(activity, 10))
        overlay.addView(ParentUi.secondaryButton(activity, p, "返回") { callbacks.onBack() })
        return overlay
    }

    fun updateProgress(newProgress: Int) {
        if (newProgress in 1..99) {
            progressBar.visibility = View.VISIBLE
            progressBar.progress = newProgress
        } else {
            progressBar.visibility = View.GONE
        }
    }

    fun hideOffline() {
        offlineOverlay.visibility = View.GONE
    }

    fun isOfflineVisible(): Boolean = offlineOverlay.visibility == View.VISIBLE

    fun showOffline(reason: String) {
        val suffix = if (reason.isNotBlank()) "\n$reason" else ""
        offlineDescView.text = "请确认设备已打开「题了个题」且在同一 Wi-Fi$suffix"
        offlineOverlay.visibility = View.VISIBLE
    }
}
