package com.studyontv.parent

import android.app.Activity
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 无线 ADB 向导页的纯代码视图构建与局部渲染器，
 * 承载品牌指南卡、局域网扫描卡、模式/安装包选择卡与实时进度卡。
 */
internal class AdbWizardViews(
    private val activity: Activity,
    private val p: ParentUi.Palette,
    prefillIp: String,
    private val callbacks: Callbacks
) {
    enum class ApkSource { AUTO_TV, AUTO_MOBILE, LOCAL_FILE }

    interface Callbacks {
        fun onBack()
        fun onScanAdb()
        fun onSelectMode(mode: TvProvisioner.Mode)
        fun onSelectApkSource(source: ApkSource)
        fun onStartProvisioning()
        fun onOpenConsole()
    }

    val rootView: View
    private lateinit var mainScroll: ScrollView
    private var selectedBrandIdx = 0
    private lateinit var brandTabsRow: LinearLayout
    private lateinit var guideStepsView: TextView
    private lateinit var guideTipView: TextView
    private lateinit var ipInput: EditText
    private lateinit var scanAdbBtn: TextView
    private lateinit var scanAdbStatus: TextView
    private lateinit var candidatesContainer: LinearLayout
    private lateinit var modeInstallBtn: TextView
    private lateinit var modeFixBtn: TextView
    private lateinit var apkSourceBox: LinearLayout
    private lateinit var srcTvBtn: TextView
    private lateinit var srcMobileBtn: TextView
    private lateinit var srcLocalBtn: TextView
    private lateinit var localApkInfoView: TextView
    private lateinit var startBtn: TextView
    private lateinit var progressCard: LinearLayout
    private lateinit var approvalBanner: TextView
    private lateinit var stepsContainer: LinearLayout
    private lateinit var summaryBox: LinearLayout
    private lateinit var summaryText: TextView
    private lateinit var openConsoleBtn: TextView

    init {
        rootView = buildRoot(prefillIp)
    }

    private fun dp(value: Int): Int = ParentUi.dp(activity, value)

    private fun col(block: LinearLayout.() -> Unit = {}): LinearLayout =
        LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; block() }

    private fun row(center: Boolean = false, block: LinearLayout.() -> Unit = {}): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            if (center) gravity = Gravity.CENTER_VERTICAL
            block()
        }

    private fun label(
        text: String = "",
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
        lineMult: Float = 0f
    ): TextView = TextView(activity).apply {
        if (text.isNotEmpty()) this.text = text
        textSize = sizeSp
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setTextColor(color)
        if (lineMult > 0f) setLineSpacing(0f, lineMult)
    }

    private fun buildRoot(prefillIp: String): View {
        val outer = col { setBackgroundColor(p.bg) }
        outer.addView(buildTopBar())
        outer.addView(View(activity).apply {
            setBackgroundColor(p.border)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1)
        })

        mainScroll = ScrollView(activity).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        }
        val content = col { setPadding(dp(16), dp(14), dp(16), dp(32)) }
        content.addView(ParentUi.sectionTitle(activity, p, "1. 打开设备 ADB 调试"))
        content.addView(buildBrandGuideCard())
        content.addView(ParentUi.sectionTitle(activity, p, "2. 目标设备"))
        content.addView(buildTargetCard(prefillIp))
        content.addView(ParentUi.sectionTitle(activity, p, "3. 安装或修复"))
        content.addView(buildModeCard())
        content.addView(buildProgressCard())
        mainScroll.addView(content)
        outer.addView(mainScroll)
        return outer
    }

    private fun buildTopBar(): LinearLayout = row(center = true) {
        setBackgroundColor(p.surface)
        val pad = dp(10)
        setPadding(pad, pad, pad, pad)
        addView(label("←", 20f, p.textPrimary, bold = true).apply {
            setPadding(dp(10), dp(4), dp(10), dp(4))
            isClickable = true
            isFocusable = true
            setOnClickListener { callbacks.onBack() }
        })
        addView(label("无线 ADB 安装 / 修权限", 16.5f, p.textPrimary, bold = true).apply {
            setPadding(dp(4), 0, 0, 0)
        })
    }

    private fun buildBrandGuideCard(): LinearLayout {
        val guideCard = ParentUi.card(activity, p, paddingDp = 14)
        brandTabsRow = row()
        guideCard.addView(HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(brandTabsRow)
        })
        guideStepsView = label(sizeSp = 13f, color = p.textPrimary, lineMult = 1.35f).apply {
            setPadding(0, dp(10), 0, dp(6))
        }
        guideTipView = label(sizeSp = 12f, color = p.textMuted, lineMult = 1.25f)
        guideCard.addView(guideStepsView)
        guideCard.addView(guideTipView)
        return guideCard
    }

    private fun buildTargetCard(prefillIp: String): LinearLayout {
        val targetCard = ParentUi.card(activity, p, paddingDp = 14)
        scanAdbBtn = ParentUi.tintedButton(activity, p.accentSoft, p.accentText, "扫描 5555") {
            callbacks.onScanAdb()
        }
        scanAdbStatus = label(sizeSp = 12f, color = p.textMuted).apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        targetCard.addView(row(center = true) {
            addView(scanAdbBtn)
            addView(ParentUi.spacer(activity, 1, 10))
            addView(scanAdbStatus)
        })
        candidatesContainer = col { setPadding(0, dp(8), 0, 0) }
        targetCard.addView(candidatesContainer)
        targetCard.addView(label("设备 IP（默认端口 5555，可填 IP:端口）", 12f, p.textMuted).apply {
            setPadding(0, dp(8), 0, dp(4))
        })
        ipInput = EditText(activity).apply {
            hint = "192.168.31.66"
            setText(prefillIp)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            textSize = 15f
            setTextColor(p.textPrimary)
            setHintTextColor(p.textMuted)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = ParentUi.roundedBg(activity, p.surfaceAlt, radiusDp = 10, strokeColor = p.border)
        }
        targetCard.addView(ipInput)
        return targetCard
    }

    private fun buildModeCard(): LinearLayout {
        val modeCard = ParentUi.card(activity, p, paddingDp = 14)
        modeInstallBtn = makeModeButton("安装 / 升级\n并配权限") {
            callbacks.onSelectMode(TvProvisioner.Mode.INSTALL_AND_CONFIGURE)
        }
        modeFixBtn = makeModeButton("仅修权限\n（已安装）") {
            callbacks.onSelectMode(TvProvisioner.Mode.FIX_PERMISSIONS_ONLY)
        }
        modeCard.addView(row {
            addView(modeInstallBtn)
            addView(ParentUi.spacer(activity, 1, 10))
            addView(modeFixBtn)
        })

        apkSourceBox = col { setPadding(0, dp(12), 0, 0) }
        apkSourceBox.addView(label("安装包", 12f, p.textMuted).apply {
            setPadding(0, 0, 0, dp(8))
        })
        srcTvBtn = makeChoiceRow("自动下载 · 电视 / 盒子版") { callbacks.onSelectApkSource(ApkSource.AUTO_TV) }
        srcMobileBtn = makeChoiceRow("自动下载 · 手机 / 平板版") { callbacks.onSelectApkSource(ApkSource.AUTO_MOBILE) }
        srcLocalBtn = makeChoiceRow("选择本地 APK…") { callbacks.onSelectApkSource(ApkSource.LOCAL_FILE) }
        apkSourceBox.addView(srcTvBtn)
        apkSourceBox.addView(ParentUi.spacer(activity, 6))
        apkSourceBox.addView(srcMobileBtn)
        apkSourceBox.addView(ParentUi.spacer(activity, 6))
        apkSourceBox.addView(srcLocalBtn)

        localApkInfoView = label(sizeSp = 12f, color = p.green).apply {
            visibility = View.GONE
            setPadding(dp(6), dp(6), 0, 0)
        }
        apkSourceBox.addView(localApkInfoView)
        modeCard.addView(apkSourceBox)
        modeCard.addView(ParentUi.spacer(activity, 14))
        startBtn = ParentUi.primaryButton(activity, p, "开始安装并配权限") {
            callbacks.onStartProvisioning()
        }
        modeCard.addView(startBtn)
        return modeCard
    }

    private fun buildProgressCard(): LinearLayout {
        progressCard = ParentUi.card(activity, p, paddingDp = 14).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) }
        }
        progressCard.addView(label("进度", 15f, p.textPrimary, bold = true).apply { setPadding(0, 0, 0, dp(8)) })
        approvalBanner = label(
            "请在设备弹窗勾选「一律允许」并确认",
            13.5f, p.amber, bold = true
        ).apply {
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
            background = ParentUi.roundedBg(activity, p.amberSoft, radiusDp = 12, strokeColor = p.amber)
            visibility = View.GONE
        }
        progressCard.addView(approvalBanner)

        stepsContainer = col { setPadding(0, dp(6), 0, 0) }
        progressCard.addView(stepsContainer)

        summaryBox = col {
            visibility = View.GONE
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        summaryText = label(sizeSp = 13f, color = p.textPrimary, lineMult = 1.3f)
        summaryBox.addView(summaryText)
        summaryBox.addView(ParentUi.spacer(activity, 10))
        openConsoleBtn = ParentUi.primaryButton(activity, p, "进入后台") {
            callbacks.onOpenConsole()
        }.apply { visibility = View.GONE }
        summaryBox.addView(openConsoleBtn)
        progressCard.addView(summaryBox)
        return progressCard
    }

    private fun makeModeButton(text: String, onClick: () -> Unit): TextView =
        label(text, 13f, p.textSecondary, bold = true).apply {
            gravity = Gravity.CENTER
            val pad = dp(10)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun makeChoiceRow(text: String, onClick: () -> Unit): TextView =
        label(text, 13f, p.textPrimary).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    fun currentIpInput(): String = ipInput.text?.toString().orEmpty()

    fun renderBrandGuide() {
        brandTabsRow.removeAllViews()
        for ((idx, g) in BrandGuides.ALL.withIndex()) {
            val active = idx == selectedBrandIdx
            brandTabsRow.addView(label(g.brand, 12.5f, if (active) p.accentText else p.textSecondary, bold = active).apply {
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = ParentUi.roundedBg(activity, if (active) p.accentSoft else p.surfaceAlt, radiusDp = 99, strokeColor = if (active) p.accent else p.border, clickable = true)
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { if (idx > 0) leftMargin = dp(8) }
                setOnClickListener { selectedBrandIdx = idx; renderBrandGuide() }
            })
        }
        val current = BrandGuides.ALL[selectedBrandIdx]
        guideStepsView.text = current.steps.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
        guideTipView.visibility = if (current.tip.isBlank()) View.GONE else View.VISIBLE
        guideTipView.text = current.tip
    }

    fun renderModeAndSource(
        mode: TvProvisioner.Mode,
        apkSource: ApkSource,
        localApkLabel: String,
        hasLocalApk: Boolean,
        isRunning: Boolean
    ) {
        val isInstall = mode == TvProvisioner.Mode.INSTALL_AND_CONFIGURE
        applyToggleStyle(modeInstallBtn, isInstall)
        applyToggleStyle(modeFixBtn, !isInstall)
        apkSourceBox.visibility = if (isInstall) View.VISIBLE else View.GONE
        applyChoiceStyle(srcTvBtn, apkSource == ApkSource.AUTO_TV)
        applyChoiceStyle(srcMobileBtn, apkSource == ApkSource.AUTO_MOBILE)
        applyChoiceStyle(srcLocalBtn, apkSource == ApkSource.LOCAL_FILE)
        localApkInfoView.visibility = if (apkSource == ApkSource.LOCAL_FILE && hasLocalApk) {
            localApkInfoView.text = "已选：$localApkLabel"
            View.VISIBLE
        } else View.GONE
        if (!isRunning) {
            startBtn.isEnabled = true
            startBtn.text = if (isInstall) "开始安装并配权限" else "开始修复权限"
        }
    }

    private fun applyToggleStyle(view: TextView, selected: Boolean) {
        view.setTextColor(if (selected) p.accentText else p.textSecondary)
        view.background = ParentUi.roundedBg(activity, if (selected) p.accentSoft else p.surfaceAlt, radiusDp = 12, strokeColor = if (selected) p.accent else p.border, strokeWidthDp = if (selected) 2 else 1, clickable = true)
    }

    private fun applyChoiceStyle(view: TextView, selected: Boolean) {
        val clean = view.text.toString().removePrefix("●  ").removePrefix("○  ")
        view.text = (if (selected) "●  " else "○  ") + clean
        view.setTextColor(if (selected) p.accentText else p.textPrimary)
        view.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        view.background = ParentUi.roundedBg(activity, if (selected) p.accentSoft else p.surfaceAlt, radiusDp = 10, strokeColor = if (selected) p.accent else p.border, clickable = true)
    }

    fun setScanningState(scanning: Boolean, statusText: String, clearCandidates: Boolean = false) {
        scanAdbBtn.isEnabled = !scanning
        scanAdbStatus.text = statusText
        if (clearCandidates) candidatesContainer.removeAllViews()
    }

    fun renderCandidates(list: List<LanNet.AdbCandidate>) {
        candidatesContainer.removeAllViews()
        for ((idx, item) in list.withIndex()) {
            if (idx > 0) candidatesContainer.addView(ParentUi.spacer(activity, 6))
            val tag = if (item.studyOnTvName.isNotBlank()) " · 已装 ${item.studyOnTvName} v${item.studyOnTvVersion}" else " · 可全新装机"
            candidatesContainer.addView(ParentUi.tintedButton(activity, p.greenSoft, p.green, "点选 ${item.ip}:${item.port}$tag") {
                ipInput.setText(if (item.port == LanNet.DEFAULT_ADB_PORT) item.ip else item.host)
                Toast.makeText(activity, "已填入 ${item.ip}", Toast.LENGTH_SHORT).show()
            })
        }
    }

    fun beginProgress() {
        progressCard.visibility = View.VISIBLE
        approvalBanner.visibility = View.GONE
        summaryBox.visibility = View.GONE
        openConsoleBtn.visibility = View.GONE
        stepsContainer.removeAllViews()
        startBtn.isEnabled = false
        startBtn.text = "正在执行中，请勿离开本页…"
        scrollToBottom()
    }

    fun setApprovalBannerVisible(visible: Boolean, scroll: Boolean = false) {
        approvalBanner.visibility = if (visible) View.VISIBLE else View.GONE
        if (scroll) scrollToBottom()
    }

    fun showSummary(success: Boolean, text: String, showOpenConsole: Boolean, scroll: Boolean = false) {
        approvalBanner.visibility = View.GONE
        summaryBox.visibility = View.VISIBLE
        summaryBox.background = ParentUi.roundedBg(activity, if (success) p.greenSoft else p.redSoft, radiusDp = 12, strokeColor = if (success) p.green else p.red)
        summaryText.setTextColor(if (success) p.green else p.red)
        summaryText.text = text
        openConsoleBtn.visibility = if (showOpenConsole) View.VISIBLE else View.GONE
        if (scroll) scrollToBottom()
    }

    fun renderSteps(stepOrder: List<String>, stepMap: Map<String, TvProvisioner.StepUpdate>) {
        stepsContainer.removeAllViews()
        for (id in stepOrder) {
            val s = stepMap[id] ?: continue
            val (icon, color) = when (s.state) {
                TvProvisioner.StepState.RUNNING -> "⏳" to p.accentText
                TvProvisioner.StepState.OK -> "✅" to p.textPrimary
                TvProvisioner.StepState.WARN -> "⚠️" to p.amber
                TvProvisioner.StepState.FAIL -> "❌" to p.red
            }
            val row = col { setPadding(0, dp(5), 0, dp(5)) }
            row.addView(label("$icon  ${s.title}", 13.5f, color, bold = s.state == TvProvisioner.StepState.RUNNING))
            if (s.detail.isNotBlank()) {
                row.addView(label(s.detail, 12f, p.textMuted).apply { setPadding(dp(24), dp(2), 0, 0) })
            }
            stepsContainer.addView(row)
        }
    }

    private fun scrollToBottom() {
        mainScroll.post { mainScroll.fullScroll(View.FOCUS_DOWN) }
    }
}
