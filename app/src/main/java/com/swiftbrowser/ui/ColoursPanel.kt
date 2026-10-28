package com.swiftbrowser.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
 * Picking your own colour (2.3): a colour maker (2.5) for any colour, and a preview of it as Swift
 * will use it (an accent as made; a background kept light or dark enough for text).
 */
object ColorPicker {
    enum class Kind { ACCENT, LIGHT_BACKGROUND, DARK_BACKGROUND }

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
        fun used(c: Int) = when (kind) {
            Kind.ACCENT -> c
            Kind.LIGHT_BACKGROUND -> Palette.limitLight(c)
            Kind.DARK_BACKGROUND -> Palette.limitDark(c)
        }
        fun refresh() {
            previewBox.removeAllViews()
            val u = used(picked)
            previewBox.addView(previewOf(a, kind, u))
            val nudged = when (kind) {
                Kind.ACCENT -> Palette.customAccent(picked, false).first != picked || Palette.customAccent(picked, true).first != picked
                else -> u != picked
            }
            note.text = when {
                !nudged -> "Drag in the square and along the rainbow."
                kind == Kind.LIGHT_BACKGROUND -> "Made a little lighter, so text stays easy to read."
                kind == Kind.DARK_BACKGROUND -> "Made a little darker, so text stays easy to read."
                else -> "Shown a little deeper or lighter where text would be hard to read."
            }
        }
        col.addView(previewBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(note.apply { setPadding(0, dp(6), 0, dp(4)) })
        // The colour maker (2.5): any colour, made with a finger. The preview follows at once.
        col.addView(ColorMaker(a, picked) { c ->
            picked = c
            refresh()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        refresh()
        val title = when (kind) {
            Kind.ACCENT -> "Make your accent colour"
            Kind.LIGHT_BACKGROUND -> "Make your light background"
            Kind.DARK_BACKGROUND -> "Make your dark background"
        }
        MaterialAlertDialogBuilder(a)
            // Room for the preview and the whole maker on a small screen (Material leaves 80 dp).
            .setBackgroundInsetTop(dp(16))
            .setBackgroundInsetBottom(dp(16))
            .setTitle(title)
            .setView(android.widget.ScrollView(a).apply { addView(col) })
            .setPositiveButton("Use this") { _, _ -> onPick(used(picked)) }
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
                    // Exactly as Swift will use it (Palette.customAccent).
                    val tones = Palette.customAccent(color, night).toList()
                    addView(ColoursPanel.mock(ctx, night, current = false, overrides = mapOf(R.color.accent to tones[0], R.color.on_accent to tones[1])),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(Ui.dp(ctx, 3), 0, Ui.dp(ctx, 3), 0) })
                }
            }
            Kind.LIGHT_BACKGROUND -> ColoursPanel.mock(ctx, night = false, current = false, overrides = Palette.lightRoles(color))
            Kind.DARK_BACKGROUND -> ColoursPanel.mock(ctx, night = true, current = false, overrides = Palette.darkRoles(color))
        }
    }
}
