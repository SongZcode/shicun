package com.emojetools.app.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.emojetools.app.R
import com.emojetools.app.core.OutputAction

class SelectionActions(private val activity: Activity, additionalAction: Button? = null, action: (OutputAction) -> Unit) {
    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density + 0.5f).toInt()
    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(16))
    }
    private val count = TextView(activity).apply {
        textSize = 12f; letterSpacing = 0f; setTextColor(0xff78838b.toInt()); gravity = Gravity.CENTER
    }
    private fun style(button: Button, primary: Boolean) = button.apply {
        isAllCaps = false; textSize = 15f; letterSpacing = 0f
        setTextColor(if (primary) Color.WHITE else 0xff167c68.toInt())
        backgroundTintList = ColorStateList.valueOf(if (primary) 0xff167c68.toInt() else 0xffe4efec.toInt())
    }
    private val buttons = OutputAction.entries.map { kind -> style(Button(activity), kind == OutputAction.SAVE).apply {
        text = activity.getString(if (kind == OutputAction.SAVE) R.string.save_selected else R.string.share_asset)
        setOnClickListener { action(kind) }
    } }
    init {
        root.addView(count, LinearLayout.LayoutParams(-1, dp(24)))
        additionalAction?.let { button ->
            root.addView(style(button, false), LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(8) })
        }
        root.addView(LinearLayout(activity).apply {
            buttons.forEachIndexed { index, button -> addView(button, LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                if (index > 0) leftMargin = dp(12)
            }) }
        }, LinearLayout.LayoutParams(-1, dp(56)))
    }
    fun update(selected: Int) {
        count.text = root.context.getString(R.string.selected_count, selected)
        buttons.forEach { it.isEnabled = selected > 0; it.alpha = if (it.isEnabled) 1f else 0.45f }
    }
    fun setEnabled(enabled: Boolean, selected: Int) {
        buttons.forEach { it.isEnabled = enabled && selected > 0; it.alpha = if (it.isEnabled) 1f else 0.45f }
    }
}
