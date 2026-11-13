package com.shilapi.xcertplay.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.host.R
import kotlin.math.roundToInt

object SettingsWidgets {

    private fun TextView.applyTextStyle(context: Context, theme: SettingsTheme, bold: Boolean = false) {
        if (theme.isOverlay) {
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            includeFontPadding = false
        } else {
            typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            setLineSpacing(theme.dp(context, 3).toFloat(), 1f)
        }
    }

    data class SwitchRowResult(
        val rowView: View,
        val switch: Switch,
    )

    data class ChoiceRowResult<T>(
        val container: View,
        val radioGroup: RadioGroup,
    )

    data class ResolutionSliderResult(
        val container: View,
        val valueTextView: TextView,
        val seekBar: SeekBar,
    )

    fun createCategoryHeader(
        context: Context,
        title: String,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
    ): TextView = TextView(context).apply {
        text = title
        textSize = if (theme.isOverlay) 16f else 22f
        setTextColor(if (theme.isOverlay) theme.accent else theme.textPrimary)
        applyTextStyle(context, theme, bold = true)
    }

    /**
     * Replaces the platform switch (about a 36x14dp track) with a 60x34dp track and a 28dp thumb,
     * so the state reads at arm's length on a head unit.
     */
    fun applyLargeSwitchStyle(switch: Switch, thumbOn: Int, thumbOff: Int, trackOn: Int, trackOff: Int) {
        val density = switch.resources.displayMetrics.density
        fun px(value: Int) = (value * density).roundToInt()
        // Switch stretches the thumb to the full track height, so the inset keeps a margin around it.
        val thumbInset = px(3)
        switch.thumbDrawable = InsetDrawable(
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setSize(px(28), px(28))
                setColor(Color.WHITE)
            },
            thumbInset,
        )
        switch.trackDrawable = GradientDrawable().apply {
            cornerRadius = px(17).toFloat()
            setSize(px(60), px(34))
            setColor(Color.WHITE)
            // Same 30% track as the platform switch, so the colours match the smaller switches.
            alpha = (0.3f * 255).roundToInt()
        }
        switch.switchMinWidth = px(60)
        switch.showText = false
        switch.thumbTintList = switchStates(thumbOn, thumbOff)
        switch.trackTintList = switchStates(trackOn, trackOff)
    }

    private fun switchStates(on: Int, off: Int) = ColorStateList(
        arrayOf(
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(),
        ),
        intArrayOf(ColorUtils.setAlphaComponent(off, 0x61), on, off),
    )

    fun createSwitchRow(
        context: Context,
        label: String,
        description: String,
        checked: Boolean,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        contentDescription: String = label,
        enabled: Boolean = true,
        labelSizeSp: Float = 18f,
        badge: View? = null,
        onChanged: (Boolean) -> Unit,
    ): SwitchRowResult {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val switch = Switch(context).apply {
            isChecked = checked
            this.contentDescription = contentDescription
            isEnabled = enabled
            applyLargeSwitchStyle(this, theme.accent, theme.textSecondary, theme.accentTrack, theme.trackOff)
            if (!theme.isOverlay) minHeight = theme.dp(context, 56)
            setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
        }

        if (theme.isOverlay) {
            val labelView = TextView(context).apply {
                text = label
                textSize = labelSizeSp
                setTextColor(theme.textSecondary)
                applyTextStyle(context, theme)
            }
            row.addView(labelView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(switch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            row.setPadding(0, theme.dp(context, 12), 0, theme.dp(context, 12))
            if (badge != null) {
                val badgeParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = theme.dp(context, 14) }
                row.addView(badge, badgeParams)
            }
            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            val labelView = TextView(context).apply {
                text = label
                textSize = labelSizeSp
                setTextColor(theme.textPrimary)
                applyTextStyle(context, theme, bold = true)
            }
            textCol.addView(labelView)
            if (description.isNotBlank()) {
                val descView = TextView(context).apply {
                    text = description
                    textSize = 14f
                    setTextColor(theme.textSecondary)
                    setPadding(0, theme.dp(context, 4), theme.dp(context, 16), 0)
                    applyTextStyle(context, theme)
                }
                textCol.addView(descView)
            }
            row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(switch)
            // The whole row is the target; the switch stays out of the focus order so D-pad stops once.
            switch.isFocusable = false
            row.isFocusable = true
            row.background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(theme.ripple), null, android.graphics.drawable.ColorDrawable(Color.WHITE))
            row.foreground = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = theme.dp(context, 12).toFloat()
                    setStroke(theme.dp(context, 3), theme.focusRing)
                })
            }
            row.setOnClickListener { if (switch.isEnabled) switch.toggle() }
            switch.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            row.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Switch::class.java.name
                    info.isCheckable = true
                    info.isChecked = switch.isChecked
                    info.isEnabled = switch.isEnabled
                }
            }
        }

        return SwitchRowResult(row, switch)
    }

    /**
     * Creates an authentic Apple CarPlay squircle icon badge (rounded-square container with centered vector icon).
     */
    fun createSquircleBadge(
        context: Context,
        iconRes: Int,
        backgroundColor: Int,
        iconTint: Int = Color.WHITE,
        sizeDp: Int = 30,
    ): View {
        val density = context.resources.displayMetrics.density
        val sizePx = (sizeDp * density).roundToInt()
        val cornerRadiusPx = (sizeDp * 0.28f * density)
        val iconSizePx = (sizeDp * 0.6f * density).roundToInt()

        val container = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = cornerRadiusPx
                setColor(backgroundColor)
            }
        }
        val icon = ImageView(context).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(iconTint)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val iconParams = FrameLayout.LayoutParams(iconSizePx, iconSizePx, Gravity.CENTER)
        container.addView(icon, iconParams)
        return container
    }

    /**
     * Creates an iOS-style 1px hairline divider with configurable start inset.
     */
    fun createDivider(
        context: Context,
        theme: SettingsTheme,
        insetStartDp: Int = 16,
    ): View = View(context).apply {
        val density = context.resources.displayMetrics.density
        val heightPx = maxOf(1, (1 * density).roundToInt())
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx).apply {
            marginStart = (insetStartDp * density).roundToInt()
        }
        layoutParams = params
        setBackgroundColor(theme.outline)
    }

    /**
     * Creates an authentic Apple CarPlay Inset Grouped Card container with rounded corners.
     */
    fun createGroupedCard(
        context: Context,
        theme: SettingsTheme,
        cornerRadiusDp: Int = 16,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val density = context.resources.displayMetrics.density
        val radiusPx = (cornerRadiusDp * density)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(theme.surface)
            setStroke(maxOf(1, (1 * density).roundToInt()), theme.outline)
        }
        setPadding((16 * density).roundToInt(), (8 * density).roundToInt(), (16 * density).roundToInt(), (8 * density).roundToInt())
    }

    /**
     * Creates an iOS-style segmented pill control for 2 to 4 options.
     */
    fun <T> createSegmentedControl(
        context: Context,
        options: List<Pair<T, String>>,
        selected: T,
        theme: SettingsTheme,
        onSelected: (T) -> Unit,
    ): View {
        val density = context.resources.displayMetrics.density
        val containerRadius = (10 * density)
        val segmentRadius = (8 * density)
        val pad = (3 * density).roundToInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = containerRadius
                setColor(if (theme.isOverlay) Color.rgb(30, 38, 48) else Color.rgb(38, 38, 40))
            }
        }

        var currentSelected = selected
        val buttons = mutableListOf<TextView>()

        fun updateStates() {
            buttons.forEach { btn ->
                @Suppress("UNCHECKED_CAST")
                val isCurrent = btn.tag as? T == currentSelected
                btn.background = if (isCurrent) {
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = segmentRadius
                        setColor(if (theme.isOverlay) theme.accent else Color.rgb(99, 99, 102))
                    }
                } else null
                btn.setTextColor(if (isCurrent) Color.WHITE else theme.textSecondary)
                btn.typeface = if (isCurrent) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
        }

        options.forEach { (value, title) ->
            val btn = TextView(context).apply {
                text = title
                textSize = 14f
                gravity = Gravity.CENTER
                tag = value
                val heightPx = (38 * density).roundToInt()
                minHeight = heightPx
                setPadding((10 * density).roundToInt(), 0, (10 * density).roundToInt(), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (currentSelected != value) {
                        currentSelected = value
                        updateStates()
                        onSelected(value)
                    }
                }
            }
            buttons.add(btn)
            container.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        updateStates()
        return container
    }

    /**
     * Creates an iOS-style navigation row with optional squircle badge, title, subtitle, right value text, and chevron.
     */
    fun createNavigationRow(
        context: Context,
        title: String,
        subtitle: String? = null,
        badge: View? = null,
        valueText: String? = null,
        theme: SettingsTheme = SettingsTheme.CARD,
        contentDescription: String = title,
        onClick: () -> Unit,
    ): View {
        val density = context.resources.displayMetrics.density
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minHeight = (54 * density).roundToInt()
            setPadding(0, (10 * density).roundToInt(), 0, (10 * density).roundToInt())
            isClickable = true
            isFocusable = true
            this.contentDescription = contentDescription
            background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(theme.ripple),
                null,
                android.graphics.drawable.ColorDrawable(Color.WHITE),
            )
            setOnClickListener { onClick() }
        }

        if (badge != null) {
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = (14 * density).roundToInt() }
            row.addView(badge, params)
        }

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val titleView = TextView(context).apply {
            text = title
            textSize = 17f
            setTextColor(theme.textPrimary)
            applyTextStyle(context, theme, bold = true)
        }
        textCol.addView(titleView)

        if (!subtitle.isNullOrBlank()) {
            val subView = TextView(context).apply {
                text = subtitle
                textSize = 13f
                setTextColor(theme.textSecondary)
                setPadding(0, (2 * density).roundToInt(), 0, 0)
                applyTextStyle(context, theme)
            }
            textCol.addView(subView)
        }
        row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (!valueText.isNullOrBlank()) {
            val valueView = TextView(context).apply {
                text = valueText
                textSize = 15f
                setTextColor(theme.textSecondary)
                applyTextStyle(context, theme)
                setPadding((6 * density).roundToInt(), 0, (6 * density).roundToInt(), 0)
            }
            row.addView(valueView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val chevron = ImageView(context).apply {
            setImageResource(R.drawable.ic_dp_chevron)
            imageTintList = ColorStateList.valueOf(theme.textSecondary)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val chevronParams = LinearLayout.LayoutParams((18 * density).roundToInt(), (18 * density).roundToInt()).apply {
            marginStart = (4 * density).roundToInt()
        }
        row.addView(chevron, chevronParams)

        return row
    }

    fun <T> createChoiceRow(
        context: Context,
        label: String,
        options: List<Pair<T, String>>,
        selected: T,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        onSelected: (T) -> Unit,
    ): ChoiceRowResult<T> {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val labelView = TextView(context).apply {
            text = label
            textSize = 18f
            setTextColor(if (theme.isOverlay) theme.textSecondary else theme.textPrimary)
            applyTextStyle(context, theme, bold = !theme.isOverlay)
        }
        container.addView(
            labelView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val group = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, theme.dp(context, 4), 0, 0)
        }
        var selectedId = View.NO_ID
        for ((value, text) in options) {
            val button = RadioButton(context).apply {
                id = View.generateViewId()
                this.text = text
                textSize = 17f
                setTextColor(theme.textSecondary)
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(theme.accent, theme.textSecondary),
                )
                tag = value
                isChecked = value == selected
            }
            if (value == selected) selectedId = button.id
            group.addView(
                button,
                RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        if (selectedId != View.NO_ID) group.check(selectedId)
        var lastSelectedId = group.checkedRadioButtonId
        group.setOnCheckedChangeListener { radioGroup, checkedId ->
            if (checkedId == View.NO_ID) {
                lastSelectedId = View.NO_ID
                return@setOnCheckedChangeListener
            }
            val button = radioGroup.findViewById<RadioButton>(checkedId) ?: return@setOnCheckedChangeListener
            // RadioGroup may notify while unchecking the old button, then repeat the new ID.
            if (!button.isChecked || checkedId == lastSelectedId) return@setOnCheckedChangeListener
            @Suppress("UNCHECKED_CAST")
            val value = button.tag as? T ?: return@setOnCheckedChangeListener
            lastSelectedId = checkedId
            onSelected(value)
        }
        container.addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return ChoiceRowResult(container, group)
    }

    fun createResolutionSlider(
        context: Context,
        initialPercent: Int,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        onPercentChanged: (Int) -> Unit,
    ): ResolutionSliderResult {
        val initial = initialPercent.coerceIn(CarPlayDisplayScale.MIN_PERCENT, CarPlayDisplayScale.MAX_PERCENT)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(context).apply {
            text = context.getString(R.string.resolution)
            textSize = 20f
            setTextColor(if (theme.isOverlay) theme.textSecondary else theme.textPrimary)
            applyTextStyle(context, theme, bold = !theme.isOverlay)
        }
        header.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val valueTextView = TextView(context).apply {
            text = "$initial%"
            textSize = 28f
            setTextColor(theme.accent)
            applyTextStyle(context, theme, bold = true)
        }
        header.addView(
            valueTextView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        container.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = theme.dp(context, 14) },
        )

        val seekBar = SeekBar(context).apply {
            max = CarPlayDisplayScale.MAX_PERCENT - CarPlayDisplayScale.MIN_PERCENT
            progress = initial - CarPlayDisplayScale.MIN_PERCENT
            splitTrack = false
            progressTintList = ColorStateList.valueOf(theme.accent)
            thumbTintList = ColorStateList.valueOf(theme.accent)
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                        val percent = (CarPlayDisplayScale.MIN_PERCENT + progress)
                            .coerceIn(CarPlayDisplayScale.MIN_PERCENT, CarPlayDisplayScale.MAX_PERCENT)
                        valueTextView.text = "$percent%"
                        onPercentChanged(percent)
                    }

                    override fun onStartTrackingTouch(sb: SeekBar) = Unit
                    override fun onStopTrackingTouch(sb: SeekBar) = Unit
                },
            )
        }
        container.addView(
            seekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = theme.dp(context, 8) },
        )

        val range = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val minLabel = TextView(context).apply {
            text = context.getString(R.string.custom_resolution_summary, CarPlayDisplayScale.MIN_PERCENT)
            textSize = 15f
            setTextColor(theme.textSecondary)
            applyTextStyle(context, theme)
        }
        range.addView(minLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val maxLabel = TextView(context).apply {
            text = context.getString(R.string.custom_resolution_summary, CarPlayDisplayScale.MAX_PERCENT)
            textSize = 15f
            setTextColor(theme.textSecondary)
            applyTextStyle(context, theme)
        }
        range.addView(
            maxLabel,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        container.addView(
            range,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        return ResolutionSliderResult(container, valueTextView, seekBar)
    }
}
