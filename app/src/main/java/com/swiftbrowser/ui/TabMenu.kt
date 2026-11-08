package com.swiftbrowser.ui

import com.swiftbrowser.R
import com.swiftbrowser.core.Favicons
import com.swiftbrowser.media.Helper
import com.swiftbrowser.tabs.Tab
import com.swiftbrowser.tabs.TabManager

/**
 * A tab's menu, from holding its card in the tab grid or (3.0.4) its chip in the group strip: its
 * group, the tabs it opened, pinning, keeping it loaded, its sound, closing it.
 */
object TabMenu {
    /** [inGrid]: from the tab grid (its messages need no room for the address bar); [changed]: a mark on it changed. */
    fun show(a: MainActivity, tab: Tab, title: String = tab.displayTitle, inGrid: Boolean = false, changed: () -> Unit = {}) {
        val say = { text: String, long: Boolean -> if (inGrid) Messages.show(a, text, long = long) else a.snack(text, long = long) }
        val actions = ArrayList<Sheets.Action>()
        if (tab.groupId == null) {
            actions += Sheets.Action(R.drawable.ic_layers, "Add to group") { Sheets.chooseGroup(a, tab) }
        } else {
            actions += Sheets.Action(R.drawable.ic_layers, "Move to another group") { Sheets.chooseGroup(a, tab) }
            actions += Sheets.Action(R.drawable.ic_close, "Take out of the group") { TabManager.removeFromGroup(tab) }
        }
        // 3.0: the tabs this one opened (pop-ups of a film site, links opened in new tabs), with one Undo.
        // 3.0.4: not pinned ones.
        val opened = TabManager.listedWith(tab).filter { it.parentId == tab.id && it !== tab && !it.pinned }
        if (opened.isNotEmpty()) {
            val label = if (opened.size == 1) "Close the tab it opened" else "Close ${opened.size} tabs it opened"
            actions += Sheets.Action(R.drawable.ic_close, label) { TabManager.closeTabs(opened, undoable = true) }
        }
        // 3.0.4: pinned, it comes first and stays open when all tabs are closed. While it is, the pin
        // is filled (as a favourite's star is), like the one on its card.
        actions += if (tab.pinned) {
            Sheets.Action(R.drawable.ic_pin_on, "Unpin tab") { TabManager.setPinned(tab, false) }
        } else {
            Sheets.Action(R.drawable.ic_pin, "Pin tab") {
                val grouped = tab.groupId != null
                TabManager.setPinned(tab, true)
                say(if (grouped) "Pinned, and taken out of its group. Pinned tabs come first and stay open when you close all tabs." else "Pinned. Pinned tabs come first and stay open when you close all tabs.", false)
            }
        }
        // 3.0.1: "Keep loaded": Swift never unloads its page to save memory.
        actions += if (tab.kept) {
            // 3.0.4: filled while kept, like the mark on its card.
            Sheets.Action(R.drawable.ic_memory_on, "Let Swift unload it") {
                TabManager.setKept(tab, false)
                changed()
            }
        } else {
            Sheets.Action(R.drawable.ic_memory, "Keep loaded") {
                TabManager.setKept(tab, true)
                changed()
                say("Swift won't unload this tab. If the phone runs out of memory, Android can still close its page.", true)
            }
        }
        // 3.0.4: its sound, whatever plays in it now or later.
        if (tab.muted) {
            actions += Sheets.Action(R.drawable.ic_volume, "Unmute tab") {
                Helper.setMuted(tab, false)
                changed()
            }
        } else if (!tab.isHome) {
            actions += Sheets.Action(R.drawable.ic_volume_off, "Mute tab") {
                Helper.setMuted(tab, true)
                changed()
            }
        }
        actions += Sheets.Action(R.drawable.ic_close, "Close tab", danger = true) { TabManager.close(tab, undoable = true) }
        Sheets.actions(a, title, null, actions, Favicons.cached(tab.url))
    }
}
