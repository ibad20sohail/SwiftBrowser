package com.swiftbrowser.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.Favicons
import com.swiftbrowser.core.Profiles
import com.swiftbrowser.core.TopSites
import com.swiftbrowser.tabs.TabManager

/** The home page of new tabs: logo, a Google search box and shortcuts to the most visited sites. */
class HomeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    interface Listener {
        fun onSearchBoxClicked()
        fun onVoiceSearch()
        fun onTileClicked(url: String)
        fun onTileLongClicked(tile: TopSites.Tile, anchor: View)
    }

    var listener: Listener? = null

    private val topSpace: Space
    private val logo: View
    private val incognitoBox: View
    private val search: LinearLayout
    private val hint: TextView
    private val tilesBox: LinearLayout
    private var incognito = false
    private var generation = 0
    private val tileViews = HashMap<String, View>()

    private val iconListener: (String) -> Unit = { key -> tileViews[key]?.let { bindIcon(it, key) } }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_home, this, true)
        topSpace = findViewById(R.id.home_top_space)
        logo = findViewById(R.id.home_logo)
        incognitoBox = findViewById(R.id.home_incognito)
        search = findViewById(R.id.home_search)
        hint = findViewById(R.id.home_search_hint)
        tilesBox = findViewById(R.id.home_tiles)
        search.setOnClickListener { listener?.onSearchBoxClicked() }
        findViewById<View>(R.id.home_mic).setOnClickListener { listener?.onVoiceSearch() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Favicons.addListener(iconListener)
    }

    override fun onDetachedFromWindow() {
        Favicons.removeListener(iconListener)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h != oldh) post { updateTopSpace() }
    }

    private fun updateTopSpace() {
        // Logo and search box sit a bit above the middle, as in Chrome; less room when the keyboard is up.
        val wanted = (height * if (incognito) 0.07f else 0.15f).toInt()
        val lp = topSpace.layoutParams
        if (lp.height != wanted) {
            lp.height = wanted
            topSpace.layoutParams = lp
        }
    }

    /** Shows the normal or the incognito home page, refreshing the shortcuts. */
    fun show(incognito: Boolean) {
        val changed = this.incognito != incognito
        this.incognito = incognito
        setBackgroundColor(color(if (incognito) R.color.incognito_bg else R.color.home_bg))
        logo.visibility = if (incognito) View.GONE else View.VISIBLE
        incognitoBox.visibility = if (incognito) View.VISIBLE else View.GONE
        tilesBox.visibility = if (incognito) View.GONE else View.VISIBLE
        if (incognito) {
            search.background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(color(R.color.incognito_url))
            }
            hint.setTextColor(color(R.color.incognito_hint))
        } else {
            search.setBackgroundResource(R.drawable.bg_home_search)
            hint.setTextColor(color(R.color.text_secondary))
            loadTiles()
        }
        if (changed) post { updateTopSpace() }
    }

    fun scrollToTop() {
        findViewById<View>(R.id.home_scroll).scrollTo(0, 0)
    }

    private fun loadTiles() {
        val gen = ++generation
        val profileId = Profiles.currentId
        val hidden = SwiftApp.prefs.hiddenTiles
        TabManager.io.execute {
            val tiles = TopSites.load(SwiftApp.db, profileId, hidden)
            post { if (gen == generation) bindTiles(tiles) }
        }
    }

    private fun bindTiles(tiles: List<TopSites.Tile>) {
        tilesBox.removeAllViews()
        tileViews.clear()
        val inflater = LayoutInflater.from(context)
        for (rowTiles in tiles.chunked(4)) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (t in rowTiles) {
                val v = inflater.inflate(R.layout.item_tile, row, false)
                v.findViewById<TextView>(R.id.tile_label).text = t.label
                val letter = v.findViewById<TextView>(R.id.tile_letter)
                letter.text = t.label.take(1).uppercase()
                letter.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(letterColor(t.key))
                }
                v.setOnClickListener { listener?.onTileClicked(t.url) }
                v.setOnLongClickListener {
                    listener?.onTileLongClicked(t, v)
                    true
                }
                v.tag = t
                tileViews[t.key] = v
                bindIcon(v, t.key)
                row.addView(v)
            }
            repeat(4 - rowTiles.size) { row.addView(Space(context), LinearLayout.LayoutParams(0, 1, 1f)) }
            tilesBox.addView(row)
        }
    }

    private fun bindIcon(v: View, key: String) {
        val tile = v.tag as? TopSites.Tile ?: return
        Favicons.load(tile.url) { bmp ->
            if (bmp == null || tileViews[key] !== v) return@load
            val icon = v.findViewById<ImageView>(R.id.tile_icon)
            icon.setImageBitmap(bmp)
            icon.visibility = View.VISIBLE
            v.findViewById<View>(R.id.tile_letter).visibility = View.GONE
        }
    }

    private fun letterColor(key: String): Int {
        val palette = Profiles.COLORS
        return palette[(key.hashCode() and 0x7fffffff) % palette.size]
    }

    private fun color(res: Int) = ContextCompat.getColor(context, res)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
