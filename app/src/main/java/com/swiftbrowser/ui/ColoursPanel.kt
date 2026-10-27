package com.swiftbrowser.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp

/**
 * Settings → Look & screen → Colours (2.3): a preview of light and dark mode side by side, the
 * accent colours (and the user's own), and the background of light and of dark mode. Choosing one
 * redraws the screen in it.
 */
object ColoursPanel {
    private const val COLUMNS = 7

    fun build(a: Activity): View {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(a, 14), Ui.dp(a, 14), Ui.dp(a, 14), Ui.dp(a, 16))
            // Made with the chosen colours already; the swatches show exact colours.
            setTag(R.id.palette_keep, true)
        }
        box.addView(preview(a))
        val prefs = SwiftApp.prefs
        val accent = Accent.of(prefs.accent)

        val preset = PRESETS.firstOrNull { it.accent == accent && it.light == prefs.lightBg && it.dark == prefs.darkBg }
        box.addView(heading(a, "Ready-made", preset?.label ?: "Your mix"))
        box.addView(presets(a, preset))

        box.addView(heading(a, "Accent", accent.label))
        val accents = Accent.entries.map { acc ->
            swatch(a, if (acc == Accent.CUSTOM) prefs.accentCustom else acc.seed, acc == accent, round = true,
                label = acc.label, custom = acc == Accent.CUSTOM) {
                if (acc == Accent.CUSTOM) {
                    ColorPicker.show(a, ColorPicker.Kind.ACCENT, prefs.accentCustom) { Looks.setAccent(a, Accent.CUSTOM, it) }
                } else {
                    Looks.setAccent(a, acc)
                }
            }
        }
        box.addView(grid(a, accents))

        val lightKey = prefs.lightBg
        box.addView(heading(a, "Light mode background", labelOf(Palette.lightBackgrounds, lightKey)))
        box.addView(backgroundRow(a, night = false, Palette.lightBackgrounds, lightKey, prefs.lightBgCustom))
        val darkKey = prefs.darkBg
        box.addView(heading(a, "Dark mode background", labelOf(Palette.darkBackgrounds, darkKey)))
        box.addView(backgroundRow(a, night = true, Palette.darkBackgrounds, darkKey, prefs.darkBgCustom))
        return box
    }

    /** A ready-made combination of colours (2.3). */
    private class Preset(val label: String, val accent: Accent, val light: String, val dark: String)

    private val PRESETS = listOf(
        Preset("Classic", Accent.BLUE, "white", "grey"),
        Preset("Paper & teal", Accent.TEAL, "warm", "warm"),
        Preset("AMOLED", Accent.CYAN, "white", "black"),
        Preset("Midnight", Accent.INDIGO, "cool", "navy"),
        Preset("Sunset", Accent.ORANGE, "warm", "warm"),
        Preset("Forest", Accent.GREEN, "cool", "grey"),
        Preset("Rose", Accent.PINK, "white", "black"),
    )

    /** The combinations in a row that scrolls sideways: each a light half over a dark half, with its accent. */
    private fun presets(a: Activity, chosen: Preset?): View = android.widget.HorizontalScrollView(a).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            for (p in PRESETS) addView(presetCard(a, p, p === chosen), LinearLayout.LayoutParams(Ui.dp(a, 84), ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    private fun presetCard(a: Activity, p: Preset, chosen: Boolean): View = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(Ui.dp(a, 4), Ui.dp(a, 2), Ui.dp(a, 4), Ui.dp(a, 2))
        val light = Palette.lightBackgrounds.first { it.key == p.light }.seed
        val dark = Palette.darkBackgrounds.first { it.key == p.dark }.seed
        val tile = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = Ui.dpf(a, 14f)
                setColor(dark)
                if (chosen) setStroke(Ui.dp(a, 2), Palette.accentColors(a, p.accent, Palette.isNight(a))[0])
                else setStroke(Ui.dp(a, 1), Palette.color(a, R.color.outline))
            }
            clipToOutline = true
            setPadding(if (chosen) Ui.dp(a, 2) else Ui.dp(a, 1), if (chosen) Ui.dp(a, 2) else Ui.dp(a, 1), if (chosen) Ui.dp(a, 2) else Ui.dp(a, 1), 0)
            fun half(bg: Int, night: Boolean) = FrameLayout(a).apply {
                setBackgroundColor(bg)
                addView(View(a).apply { background = Ui.circle(Palette.accentColors(a, p.accent, night)[0]) },
                    FrameLayout.LayoutParams(Ui.dp(a, 14), Ui.dp(a, 14), Gravity.CENTER))
            }
            addView(half(light, false), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 26)))
            addView(half(dark, true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 26)))
        }
        addView(tile, LinearLayout.LayoutParams(Ui.dp(a, 64), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(Ui.text(a, Ui.Type.CAPTION, p.label, lines = 2).apply {
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(a, 6), 0, 0)
            if (chosen) setTextColor(Ui.accent(a))
        })
        contentDescription = p.label + if (chosen) ", chosen" else ""
        background = Ui.ripple(a, null, 12)
        Ui.pressable(this, 0.92f)
        setOnClickListener {
            Haptics.tick(this)
            Looks.setColours(a, p.accent, p.light, p.dark)
        }
    }

    private fun labelOf(list: List<Palette.Background>, key: String) =
        if (key == Palette.CUSTOM) "Your colour" else list.firstOrNull { it.key == key }?.label ?: list[0].label

    private fun heading(a: Context, title: String, value: String): View = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(Ui.dp(a, 2), Ui.dp(a, 18), Ui.dp(a, 2), Ui.dp(a, 8))
        addView(Ui.text(a, Ui.Type.LABEL, title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(Ui.text(a, Ui.Type.CAPTION, value))
    }

    /** Swatches in rows of [COLUMNS], each centred in an equal share of the row. */
    private fun grid(a: Context, views: List<View>): View = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        for (row in views.chunked(COLUMNS)) {
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                for (v in row) addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                repeat(COLUMNS - row.size) { addView(View(a), LinearLayout.LayoutParams(0, 1, 1f)) }
            })
        }
    }

    /**
     * A colour to tap: round for accents, rounded squares for backgrounds (with their name under them).
     * The chosen one has a tick; the user's own shows a pencil until it's chosen.
     */
    private fun swatch(a: Context, color: Int, chosen: Boolean, round: Boolean, label: String, custom: Boolean, caption: Boolean = false, onClick: () -> Unit): View {
        val size = Ui.dp(a, if (round) 36 else 44)
        val shape = GradientDrawable().apply {
            if (round) shape = GradientDrawable.OVAL else cornerRadius = Ui.dpf(a, 12f)
            setColor(color)
            // Light colours on a light card (and dark on dark) still show their edge.
            setStroke(Ui.dp(a, if (chosen) 2 else 1), if (chosen) Ui.accent(a) else Palette.color(a, R.color.outline))
        }
        val mark = when {
            chosen -> R.drawable.ic_check
            custom -> R.drawable.ic_edit
            else -> 0
        }
        val ink = if (Color.luminance(color) > 0.45f) Color.BLACK else Color.WHITE
        val image = ImageView(a).apply {
            background = shape
            scaleType = ImageView.ScaleType.CENTER
            if (mark != 0) setImageDrawable(Ui.icon(a, mark, ink))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        return LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 4))
            contentDescription = label + if (chosen) ", chosen" else ""
            isClickable = true
            background = Ui.ripple(a, null, 12)
            addView(image, LinearLayout.LayoutParams(size, size))
            // Two lines when five share a row ("Pure black", "Warm dark").
            if (caption) addView(Ui.text(a, Ui.Type.CAPTION, label, lines = 2).apply {
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1f)
                setPadding(Ui.dp(a, 2), Ui.dp(a, 6), Ui.dp(a, 2), 0)
            })
            Ui.pressable(this, 0.9f)
            setOnClickListener {
                Haptics.tick(this)
                onClick()
            }
        }
    }

    private fun backgroundRow(a: Activity, night: Boolean, list: List<Palette.Background>, key: String, custom: Int): View {
        val views = list.map { bg ->
            swatch(a, bg.seed, bg.key == key, round = false, label = bg.label, custom = false, caption = true) {
                if (bg.key != key) Looks.setBackground(a, night, bg.key)
            }
        } + swatch(a, custom, key == Palette.CUSTOM, round = false, label = "Your colour", custom = true, caption = true) {
            val kind = if (night) ColorPicker.Kind.DARK_BACKGROUND else ColorPicker.Kind.LIGHT_BACKGROUND
            ColorPicker.show(a, kind, custom) { Looks.setBackground(a, night, Palette.CUSTOM, it) }
        }
        return LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            for (v in views) addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    /** Light and dark mode side by side, in the chosen colours: a bar, a card, a button and a switch. */
    private fun preview(a: Context): View = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        val now = Palette.isNight(a)
        addView(mock(a, night = false, current = !now), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(a, 6) })
        addView(mock(a, night = true, current = now), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(a, 6) })
    }

    fun mock(a: Context, night: Boolean, current: Boolean, overrides: Map<Int, Int> = emptyMap()): View {
        fun c(res: Int) = overrides[res] ?: Palette.colorFor(a, night, res)
        val dp = { v: Int -> Ui.dp(a, v) }
        val frame = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = Ui.dpf(a, 16f)
                setColor(c(R.color.page_bg))
                setStroke(dp(1), c(R.color.divider))
            }
            clipToOutline = true
            // The outline shows all round, also where the bar is the same colour as the card.
            setPadding(dp(1), dp(1), dp(1), dp(1))
        }
        // The bar: an address field and the accent's mark.
        frame.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(c(R.color.toolbar_bg))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(View(a).apply { background = Ui.shape(a, c(R.color.surface_alt), 10) }, LinearLayout.LayoutParams(0, dp(18), 1f))
            addView(View(a).apply { background = Ui.circle(c(R.color.accent)) }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginStart = dp(8) })
        })
        // A card with text, a button and a switch that's on.
        frame.addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.shape(a, c(R.color.card_bg), 12)
            setPadding(dp(10), dp(8), dp(10), dp(10))
            addView(TextView(a).apply {
                text = if (night) "Dark" else "Light"
                textSize = 13f
                setTypeface(Ui.MEDIUM)
                setTextColor(c(R.color.text_primary))
            })
            addView(TextView(a).apply {
                text = if (current) "Now" else " "
                textSize = 11f
                setTextColor(c(R.color.text_secondary))
            })
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
                addView(TextView(a).apply {
                    text = "OK"
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTypeface(Ui.MEDIUM)
                    setTextColor(c(R.color.on_accent))
                    background = Ui.shape(a, c(R.color.accent), 12)
                }, LinearLayout.LayoutParams(dp(40), dp(22)))
                addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
                // A switch that's on, in the accent.
                addView(FrameLayout(a).apply {
                    background = Ui.shape(a, c(R.color.accent), 10)
                    addView(View(a).apply { background = Ui.circle(c(R.color.on_accent)) }, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(3) })
                }, LinearLayout.LayoutParams(dp(32), dp(20)))
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(8), dp(8), dp(8), dp(8))
        })
        return frame
    }
}

/**
 * Picking your own colour (2.3): a grid of colours made for what it's for (bright for an accent,
 * very light or very dark for a background), a box for a colour code, and a preview.
 */
object ColorPicker {
    enum class Kind { ACCENT, LIGHT_BACKGROUND, DARK_BACKGROUND }

    /** The colours offered: 14 hues in a few strengths, and greys. */
    private fun choices(kind: Kind): List<Int> {
        val hues = (0 until 14).map { Color.HSVToColor(floatArrayOf(it * 360f / 14, 0.85f, 0.9f)) }
        return when (kind) {
            Kind.ACCENT -> listOf(50.0, 38.0, 64.0).flatMap { t -> hues.map { Tones.tone(it, t) } } +
                listOf(0xFF2B2F36, 0xFF4B515C, 0xFF6B7280, 0xFF8A6A50, 0xFF5A6B4E, 0xFF4E5D6B, 0xFF6B4E66).map { it.toInt() }
            Kind.LIGHT_BACKGROUND -> listOf(0.95, 0.9).flatMap { t -> hues.map { Palette.limitLight(Tones.mix(it, Color.WHITE, t)) } } +
                listOf(0xFFFFFFFF, 0xFFFAFAFA, 0xFFF5F5F5, 0xFFFDF8EE, 0xFFF6F4EF, 0xFFF2F5F8, 0xFFF4F1F8).map { it.toInt() }
            Kind.DARK_BACKGROUND -> listOf(0.9, 0.83).flatMap { t -> hues.map { Palette.limitDark(Tones.mix(it, Color.BLACK, t)) } } +
                listOf(0xFF000000, 0xFF0A0A0A, 0xFF121212, 0xFF15171C, 0xFF111418, 0xFF161311, 0xFF0F1115).map { it.toInt() }
        }
    }

    fun show(a: Activity, kind: Kind, initial: Int, onPick: (Int) -> Unit) {
        var picked = initial or 0xFF000000.toInt()
        val dp = { v: Int -> Ui.dp(a, v) }
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
            setTag(R.id.palette_keep, true)
        }
        // The preview: the colour as it will look (an accent in light and dark, or a page).
        val previewBox = FrameLayout(a)
        val note = Ui.text(a, Ui.Type.CAPTION, "")
        val hex = EditText(a).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            textSize = 15f
            hint = "#RRGGBB"
            setTextColor(Ui.text(a))
            setHintTextColor(Ui.textSecondary(a))
        }
        val cells = ArrayList<Pair<ImageView, Int>>()
        var updatingHex = false

        fun refresh() {
            previewBox.removeAllViews()
            val (used, adjusted) = when (kind) {
                Kind.ACCENT -> picked to false
                Kind.LIGHT_BACKGROUND -> Palette.limitLight(picked).let { it to (it != picked) }
                Kind.DARK_BACKGROUND -> Palette.limitDark(picked).let { it to (it != picked) }
            }
            previewBox.addView(previewOf(a, kind, used))
            note.text = when {
                adjusted && kind == Kind.LIGHT_BACKGROUND -> "Made a little lighter, so text stays easy to read."
                adjusted -> "Made a little darker, so text stays easy to read."
                else -> " "
            }
            for ((cell, c) in cells) {
                cell.setImageDrawable(if (c == picked) Ui.icon(a, R.drawable.ic_check, if (Color.luminance(c) > 0.45f) Color.BLACK else Color.WHITE) else null)
            }
            if (!updatingHex) {
                updatingHex = true
                hex.setText(String.format("#%06X", picked and 0xFFFFFF))
                hex.setSelection(hex.text.length)
                updatingHex = false
            }
        }

        col.addView(previewBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(note.apply { setPadding(0, dp(6), 0, dp(6)) })
        val grid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        for (row in choices(kind).chunked(7)) {
            grid.addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                for (c in row) {
                    val cell = ImageView(a).apply {
                        background = GradientDrawable().apply {
                            shape = if (kind == Kind.ACCENT) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
                            if (kind != Kind.ACCENT) cornerRadius = Ui.dpf(a, 8f)
                            setColor(c)
                            setStroke(dp(1), Palette.color(a, R.color.outline))
                        }
                        scaleType = ImageView.ScaleType.CENTER
                        contentDescription = String.format("#%06X", c and 0xFFFFFF)
                        setOnClickListener {
                            picked = c
                            Haptics.tick(this)
                            refresh()
                        }
                    }
                    cells += cell to c
                    addView(FrameLayout(a).apply {
                        addView(cell, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
                    }, LinearLayout.LayoutParams(0, dp(38), 1f))
                }
                repeat(7 - row.size) { addView(View(a), LinearLayout.LayoutParams(0, 1, 1f)) }
            })
        }
        // Only the colours scroll: the preview stays in sight while choosing (2.3).
        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        body.addView(grid)
        col.addView(android.widget.ScrollView(a).apply {
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(16))
            addView(body)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
            addView(Ui.text(a, Ui.Type.BODY_SECONDARY, "Colour code"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(12) })
            addView(hex, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updatingHex) return
                val t = s?.toString()?.trim()?.removePrefix("#") ?: return
                if (t.length != 6) return
                val v = t.toLongOrNull(16) ?: return
                picked = (0xFF000000 or v).toInt()
                updatingHex = true
                refresh()
                updatingHex = false
            }
        })
        refresh()
        val title = when (kind) {
            Kind.ACCENT -> "Your accent colour"
            Kind.LIGHT_BACKGROUND -> "Light mode background"
            Kind.DARK_BACKGROUND -> "Dark mode background"
        }
        MaterialAlertDialogBuilder(a)
            .setTitle(title)
            .setView(col)
            .setPositiveButton("Use this") { _, _ ->
                onPick(
                    when (kind) {
                        Kind.ACCENT -> picked
                        Kind.LIGHT_BACKGROUND -> Palette.limitLight(picked)
                        Kind.DARK_BACKGROUND -> Palette.limitDark(picked)
                    },
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
    }

    /** What [color] will look like: an accent on both backgrounds, or a background with text on it. */
    private fun previewOf(a: Context, kind: Kind, color: Int): View {
        val ctx = a
        return when (kind) {
            Kind.ACCENT -> LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                for (night in listOf(false, true)) {
                    val tones = if (night) {
                        listOf(Tones.tone(color, 80.0), Tones.tone(color, 20.0))
                    } else {
                        listOf(Tones.tone(color, 40.0), Color.WHITE)
                    }
                    addView(ColoursPanel.mock(ctx, night, current = false, overrides = mapOf(R.color.accent to tones[0], R.color.on_accent to tones[1])),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(Ui.dp(ctx, 3), 0, Ui.dp(ctx, 3), 0) })
                }
            }
            Kind.LIGHT_BACKGROUND -> ColoursPanel.mock(ctx, night = false, current = false, overrides = Palette.lightRoles(color))
            Kind.DARK_BACKGROUND -> ColoursPanel.mock(ctx, night = true, current = false, overrides = Palette.darkRoles(color))
        }
    }
}
