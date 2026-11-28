package com.studyontv.tv

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * 悬浮层内部原生输入框：不拉起新 Activity、不卸载悬浮层，
 * 避免部分电视系统（如 Android 14）在切 Activity 时触发后台清理导致进程被杀或答案丢失。
 */
internal class GateOverlayInput(private val context: Context) {
    private var panel: View? = null

    fun show(
        host: FrameLayout,
        targetWeb: WebView,
        draft: String,
        prompt: String,
        passwordMode: Boolean
    ) {
        dismiss(targetWeb, notifyCancel = false)
        val dp = context.resources.displayMetrics.density
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setBackgroundColor(TvUi.background(context))
            isClickable = true
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((64 * dp).toInt(), (24 * dp).toInt(), (64 * dp).toInt(), (24 * dp).toInt())
        }
        content.addView(TextView(context).apply {
            text = if (passwordMode) "家长验证" else "填写答案"
            textSize = 26f
            setTextColor(TvUi.foreground(context))
        })
        if (prompt.isNotBlank()) {
            content.addView(TextView(context).apply {
                text = prompt.take(200)
                textSize = 16f
                setTextColor(TvUi.muted(context))
                maxLines = 3
                setPadding(0, (8 * dp).toInt(), 0, (12 * dp).toInt())
            }, LinearLayout.LayoutParams(-1, -2))
        }
        lateinit var edit: EditText
        fun commit(accepted: Boolean) {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(edit.windowToken, 0)
            val text = edit.text?.toString().orEmpty()
            dismiss(targetWeb, notifyCancel = false)
            if (accepted) {
                val payload = JSONObject().put("text", text).toString()
                targetWeb.post {
                    try { targetWeb.evaluateJavascript("window.SOTV_INPUT&&SOTV_INPUT($payload)", null) } catch (_: Throwable) { }
                    targetWeb.requestFocus()
                }
            } else {
                targetWeb.post {
                    try { targetWeb.evaluateJavascript("window.SOTV_INPUT_CANCEL&&SOTV_INPUT_CANCEL()", null) } catch (_: Throwable) { }
                    targetWeb.requestFocus()
                }
            }
        }
        edit = EditText(context).apply {
            hint = if (passwordMode) "输入家长管理密码" else "用遥控器或输入法填写答案，完成后选“确定”"
            inputType = if (passwordMode) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            minLines = if (passwordMode) 1 else 2
            maxLines = if (passwordMode) 1 else 5
            gravity = Gravity.TOP or Gravity.START
            TvUi.field(this)
            setText(draft)
            setSelection(text.length)
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) { commit(true); true } else false
            }
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    commit(false); true
                } else false
            }
        }
        content.addView(edit, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() })
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun addBtn(label: String, primary: Boolean = false, action: () -> Unit) {
            val btn = TvUi.button(context, label, primary, action)
            btn.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    commit(false); true
                } else false
            }
            row.addView(btn, LinearLayout.LayoutParams(-2, -2).apply {
                leftMargin = (8 * dp).toInt(); rightMargin = (8 * dp).toInt()
            })
        }
        addBtn(if (passwordMode) "验证解锁" else "确定", true) { commit(true) }
        addBtn("清空") { edit.setText("") }
        addBtn("取消") { commit(false) }
        content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (20 * dp).toInt() })
        scroll.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        host.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        panel = scroll
        edit.requestFocus()
        edit.post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun dismiss(targetWeb: WebView?, notifyCancel: Boolean) {
        val current = panel ?: return
        panel = null
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val token = current.findFocus()?.windowToken ?: current.windowToken
        if (token != null) imm?.hideSoftInputFromWindow(token, 0)
        (current.parent as? ViewGroup)?.removeView(current)
        if (notifyCancel) {
            targetWeb?.post {
                try { targetWeb.evaluateJavascript("window.SOTV_INPUT_CANCEL&&SOTV_INPUT_CANCEL()", null) } catch (_: Throwable) { }
            }
        }
    }
}
