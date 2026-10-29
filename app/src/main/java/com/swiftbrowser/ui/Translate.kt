package com.swiftbrowser.ui

import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.L
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.tabs.Tab
import com.swiftbrowser.tabs.TabManager
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.TranslationsController.Language
import org.mozilla.geckoview.TranslationsController.RuntimeTranslation
import org.mozilla.geckoview.TranslationsController.SessionTranslation
import org.mozilla.geckoview.TranslationsController.TranslationsException
import java.util.Locale

/**
 * Translating pages (2.6), on the phone itself: Firefox's translation engine, with each language
 * downloaded the first time it is needed (about 20 MB). Nothing of the page is sent anywhere.
 */
object Translate {
    private const val PREF_TO = "translate_to"
    private const val PREF_OFFER = "translate_offer"

    /** The languages the engine can translate from and to, asked once. */
    private var from: List<Language> = emptyList()
    private var to: List<Language> = emptyList()

    /** Sites already offered in this run of Swift: the offer comes once. */
    private val offered = HashSet<String>()

    var offer: Boolean
        get() = SwiftApp.prefs.sp.getBoolean(PREF_OFFER, true)
        set(v) = SwiftApp.prefs.sp.edit().putBoolean(PREF_OFFER, v).apply()

    /** Follows what the engine knows about [tab]'s page: its language, and whether it's translated. */
    fun delegate(tab: Tab) = object : SessionTranslation.Delegate {
        override fun onTranslationStateChange(session: GeckoSession, state: SessionTranslation.TranslationState?) {
            tab.translation = state
        }

        override fun onOfferTranslate(session: GeckoSession) {
            offerFor(tab)
        }
    }

    private fun offerFor(tab: Tab) {
        if (!offer || tab !== TabManager.current) return
        val a = TabManager.ui?.activity as? MainActivity ?: return
        val site = UrlUtils.site(tab.url) ?: return
        val lang = tab.translation?.detectedLanguages?.docLangTag ?: return
        if (!offered.add(site)) return
        Messages.show(a, "This page is in ${name(lang)}", action = "Translate") { sheet(a, tab) }
    }

    private fun name(code: String?): String {
        if (code.isNullOrBlank()) return "another language"
        (from + to).firstOrNull { it.code == code }?.localizedDisplayName?.let { return it }
        return Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault()).ifBlank { code }
    }

    private fun describe(e: Throwable?): String = when ((e as? TranslationsException)?.code) {
        TranslationsException.ERROR_ENGINE_NOT_SUPPORTED -> "This phone can't run the translation engine."
        TranslationsException.ERROR_LANGUAGE_NOT_SUPPORTED -> "That language can't be translated yet."
        TranslationsException.ERROR_MODEL_COULD_NOT_DOWNLOAD, TranslationsException.ERROR_MODEL_DOWNLOAD_REQUIRED ->
            "The language couldn't be downloaded. Check the connection and try again."
        else -> "The page couldn't be translated."
    }

    /** ≡ → Translate: from the page's language to yours, or back to the original. */
    fun sheet(a: MainActivity, tab: Tab) {
        val session = tab.session
        if (session == null || !UrlUtils.isHttp(tab.url)) {
            a.snack("Open a page to translate it")
            return
        }
        RuntimeTranslation.isTranslationsEngineSupported().accept({ ok ->
            if (ok != true) {
                Dialogs.info(a, "Translate", "This phone can't run the translation engine.")
                return@accept
            }
            if (from.isEmpty() || to.isEmpty()) {
                RuntimeTranslation.listSupportedLanguages().accept({ s ->
                    from = s?.fromLanguages.orEmpty().sortedBy { it.localizedDisplayName }
                    to = s?.toLanguages.orEmpty().sortedBy { it.localizedDisplayName }
                    show(a, tab, session)
                }, { e ->
                    L.w("Couldn't list translation languages", e)
                    a.snack("Translation isn't available right now. Try again in a moment.")
                })
            } else {
                show(a, tab, session)
            }
        }, { _ -> Dialogs.info(a, "Translate", "This phone can't run the translation engine.") })
    }

    private fun show(a: MainActivity, tab: Tab, session: GeckoSession) {
        if (from.isEmpty() || to.isEmpty()) {
            Dialogs.info(a, "Translate", "No languages can be translated right now.")
            return
        }
        val state = tab.translation
        val translated = state?.requestedTranslationPair
        val detected = state?.detectedLanguages
        var source = translated?.fromLanguage ?: detected?.docLangTag?.takeIf { c -> from.any { it.code == c } } ?: from.first().code
        val mine = SwiftApp.prefs.sp.getString(PREF_TO, null) ?: detected?.userLangTag ?: Locale.getDefault().language
        var target = translated?.toLanguage ?: to.firstOrNull { it.code == mine }?.code ?: to.firstOrNull { it.code == "en" }?.code ?: to.first().code

        val col = Sheets.column(a)
        col.addView(Sheets.header(a, "Translate page", "Translated on this phone: the page isn't sent anywhere."))
        val size = Sheets.note(a, "")
        lateinit var fromRow: TextView
        lateinit var toRow: TextView
        fun checkSize() {
            size.text = ""
            if (source == target) return
            RuntimeTranslation.checkPairDownloadSize(source, target).accept({ bytes ->
                size.text = if (bytes != null && bytes > 0) "First downloads ${UrlUtils.formatBytes(bytes)}, once." else ""
            }, { _ -> })
        }
        fun label(prefix: String, code: String) = "$prefix  ${name(code)}"
        fromRow = Sheets.row(a, R.drawable.ic_translate, label("From", source)) {
            choose(a, "Translate from", from, source) { c ->
                source = c
                fromRow.text = label("From", c)
                checkSize()
            }
        }
        toRow = Sheets.row(a, R.drawable.ic_translate, label("To", target)) {
            choose(a, "Translate to", to, target) { c ->
                target = c
                toRow.text = label("To", c)
                checkSize()
            }
        }
        col.addView(fromRow)
        col.addView(toRow)
        col.addView(size)
        checkSize()

        lateinit var dialog: BottomSheetDialog
        val buttons = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(a, 16), Ui.dp(a, 8), Ui.dp(a, 16), 0)
        }
        buttons.addView(MaterialButton(a).apply {
            text = "Translate"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                dialog.dismiss()
                if (source == target) {
                    a.snack("Choose two different languages")
                    return@setOnClickListener
                }
                SwiftApp.prefs.sp.edit().putString(PREF_TO, target).apply()
                translate(a, session, source, target)
            }
        })
        if (translated != null) {
            buttons.addView(MaterialButton(a, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Show the original"
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setOnClickListener {
                    dialog.dismiss()
                    session.sessionTranslation?.restoreOriginalPage()?.accept({ }, { _ -> a.snack("The original page couldn't be shown. Reload it.") })
                }
            })
        }
        col.addView(buttons)
        dialog = Sheets.sheet(a, col)
        dialog.showImmersive()
    }

    private fun translate(a: MainActivity, session: GeckoSession, source: String, target: String) {
        val t = session.sessionTranslation ?: run {
            a.snack("The page couldn't be translated.")
            return
        }
        a.snack("Translating… The first time takes a little longer.")
        t.translate(source, target, SessionTranslation.TranslationOptions.Builder().downloadModel(true).build())
            .accept({ }, { e ->
                L.w("Translation failed", e)
                a.snack(describe(e))
            })
    }

    private fun choose(a: MainActivity, title: String, list: List<Language>, selected: String, onPick: (String) -> Unit) {
        MaterialAlertDialogBuilder(a)
            .setTitle(title)
            .setSingleChoiceItems(list.map { it.localizedDisplayName }.toTypedArray(), list.indexOfFirst { it.code == selected }) { d, which ->
                d.dismiss()
                onPick(list[which].code)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
    }

    /** Settings → Translation languages: the downloaded ones, which can be deleted to free space. */
    fun manage(a: android.app.Activity, changed: () -> Unit) {
        RuntimeTranslation.listModelDownloadStates().accept({ models ->
            val kept = models.orEmpty().filter { it.isDownloaded == true && it.language != null }
            if (kept.isEmpty()) {
                Dialogs.info(a, "Translation languages", "None downloaded. Swift downloads a language the first time you translate from or to it.")
                return@accept
            }
            MaterialAlertDialogBuilder(a)
                .setTitle("Tap one to delete it")
                .setItems(kept.map { "${it.language!!.localizedDisplayName} · ${UrlUtils.formatBytes(it.size)}" }.toTypedArray()) { _, which ->
                    val lang = kept[which].language!!
                    RuntimeTranslation.manageLanguageModel(
                        RuntimeTranslation.ModelManagementOptions.Builder()
                            .languageToManage(lang.code)
                            .operation(RuntimeTranslation.DELETE)
                            .operationLevel(RuntimeTranslation.LANGUAGE)
                            .build(),
                    ).accept({
                        Messages.show(a, "${lang.localizedDisplayName} deleted")
                        changed()
                    }, { _ -> Messages.show(a, "Couldn't delete it") })
                }
                .setNegativeButton("Close", null)
                .showImmersive()
        }, { _ -> Dialogs.info(a, "Translation languages", "This phone can't run the translation engine.") })
    }
}
