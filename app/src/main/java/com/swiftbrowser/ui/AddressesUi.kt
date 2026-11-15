package com.swiftbrowser.ui

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.Address
import com.swiftbrowser.core.AddressRow
import com.swiftbrowser.core.AddressRules
import com.swiftbrowser.core.Addresses
import com.swiftbrowser.core.Countries
import com.swiftbrowser.core.L
import com.swiftbrowser.core.Profiles
import com.swiftbrowser.tabs.Tab
import org.json.JSONObject

/**
 * 3.0.11: saved addresses (Settings → Passwords → Addresses), built like the saved passwords' list:
 * add, change and delete (with Undo), each profile its own. The engine fills them in on forms
 * (Prompts' pick sheet); after a page's form with an address is sent, Swift offers to keep it
 * ([onFormSent], from the helper's content script, as GeckoView 143 can't ask itself).
 */
object AddressesUi {
    private val io = com.swiftbrowser.core.Background.single("addresses")

    private fun later(a: Activity, work: () -> Unit) = a.window.decorView.post(work)

    /** A saved address in a list or the pick sheet: the place icon in the lists' rounded holder, the name and one line of the address. */
    fun row(ctx: Context, title: String, subtitle: String, onClick: () -> Unit): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = Ui.dp(ctx, Ui.ROW_TWO_LINES)
        setPadding(Ui.dp(ctx, Ui.XXL), Ui.dp(ctx, Ui.S), Ui.dp(ctx, Ui.XXL), Ui.dp(ctx, Ui.S))
        background = Ui.ripple(ctx, null, 0)
        addView(ImageView(ctx).apply {
            setBackgroundResource(R.drawable.bg_icon_badge)
            setImageDrawable(Ui.icon(ctx, R.drawable.ic_pic_place, Ui.textSecondary(ctx)))
            scaleType = ImageView.ScaleType.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(Ui.dp(ctx, Ui.ICON_BOX), Ui.dp(ctx, Ui.ICON_BOX)).apply { marginEnd = Ui.dp(ctx, Ui.L) })
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(ctx, Ui.Type.BODY, title, lines = 1))
            if (subtitle.isNotBlank()) addView(Ui.text(ctx, Ui.Type.CAPTION, subtitle, lines = 1))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        setOnClickListener { onClick() }
    }

    // ------------------------------------------------------------------ the list

    fun list(a: Activity) {
        io.execute {
            val read = try {
                Addresses.read(Profiles.currentId)
            } catch (e: Exception) {
                L.w("Saved addresses couldn't be opened", e)
                null
            }
            later(a) {
                if (a.isFinishing) return@later
                if (read == null) Messages.show(a, "Swift couldn't open your saved addresses just now. Try again in a moment.", long = true)
                else showList(a, read.first, read.second)
            }
        }
    }

    private fun showList(a: Activity, saved: List<Addresses.Saved>, unreadable: List<AddressRow>) {
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        val b = MaterialAlertDialogBuilder(a)
            .setTitle(if (saved.isEmpty()) "Addresses" else "Addresses (${saved.size})")
            .setPositiveButton("Add address") { _, _ -> edit(a, null) }
            .setNegativeButton("Close", null)
        if (saved.isEmpty() && unreadable.isEmpty()) {
            b.setMessage("None yet. When you send your address on a site, Swift offers to keep it and fills it in on forms next time.")
        } else {
            val rows = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            for (s in saved) {
                rows.addView(row(a, s.address.name, AddressRules.summary(s.address)) {
                    dialog.dismiss()
                    edit(a, s)
                })
            }
            // As the saved passwords: the ones the keystore can't open any more, last, in red.
            if (unreadable.isNotEmpty()) {
                val n = unreadable.size
                rows.addView(Ui.text(a, Ui.Type.BODY, if (n == 1) "1 address can't be read any more" else "$n addresses can't be read any more", Ui.danger(a)).apply {
                    minHeight = Ui.dp(a, Ui.ROW_ONE_LINE)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(Ui.dp(a, Ui.XXL), Ui.dp(a, Ui.S), Ui.dp(a, Ui.XXL), Ui.dp(a, Ui.S))
                    background = Ui.ripple(a, null, 0)
                    setOnClickListener {
                        dialog.dismiss()
                        deleteUnreadable(a, unreadable)
                    }
                })
            }
            b.setView(ScrollView(a).apply {
                isVerticalScrollBarEnabled = false
                addView(rows)
            })
        }
        dialog = b.showImmersive()
    }

    private fun deleteUnreadable(a: Activity, rows: List<AddressRow>) {
        Dialogs.confirm(
            a, if (rows.size == 1) "This address can't be read any more" else "These addresses can't be read any more",
            "Android's secure storage lost the key they were locked with, so Swift can't open them again. Delete them; Swift offers to save each address again the next time you send it.",
            if (rows.size == 1) "Delete it" else "Delete them", danger = true,
        ) {
            io.execute {
                Addresses.removeRows(rows)
                later(a) { Messages.show(a, "Deleted") }
            }
        }
    }

    // ------------------------------------------------------------------ adding and changing one

    /** The fields of [s] (null: a new address), with Save, and Delete for one saved. */
    private fun edit(a: Activity, s: Addresses.Saved?) {
        val start = s?.address ?: Address(country = phoneCountry(a))
        fun box(hint: String, value: String, type: Int) = EditText(a).apply {
            this.hint = hint
            setText(value)
            inputType = type
            if (type and InputType.TYPE_TEXT_FLAG_MULTI_LINE == 0) setSingleLine() else maxLines = 4
            Ui.field(this)
        }
        val words = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        val name = box("Name", start.name, words or InputType.TYPE_TEXT_VARIATION_PERSON_NAME)
        val org = box("Organisation (optional)", start.organization, words)
        val street = box("Street address", start.street, words or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        val city = box("City", start.city, words or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS)
        val region = box("State or region", start.region, words or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS)
        val postcode = box("Postcode", start.postcode, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        var country = start.country
        // A field to tap: the list of countries opens.
        val countryBox = box("Country", if (country.isEmpty()) "" else Countries.name(country), InputType.TYPE_NULL).apply {
            keyListener = null
            isFocusable = false
            setOnClickListener {
                val all = Countries.all()
                // The phone's own country first: it's the one most often wanted.
                val mine = phoneCountry(a)
                val list = all.filter { it.first == mine } + all.filter { it.first != mine }
                MaterialAlertDialogBuilder(a)
                    .setTitle("Country")
                    .setItems(list.map { it.second }.toTypedArray()) { _, which ->
                        country = list[which].first
                        setText(list[which].second)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .showImmersive()
            }
        }
        val tel = box("Phone", start.tel, InputType.TYPE_CLASS_PHONE)
        val email = box("Email", start.email, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val error = Ui.text(a, Ui.Type.CAPTION, "").apply { setTextColor(Ui.danger(a)) }
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(a, Ui.XXL), Ui.dp(a, Ui.XS), Ui.dp(a, Ui.XXL), Ui.dp(a, Ui.S))
            addView(Ui.text(a, Ui.Type.BODY_SECONDARY, "Swift fills it in on forms. A name, a street and a postcode are needed.").apply {
                setPadding(0, 0, 0, Ui.dp(a, Ui.S))
            })
            for ((i, v) in listOf(name, org, street, city, region, postcode, countryBox, tel, email).withIndex()) {
                addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (i > 0) topMargin = Ui.dp(a, Ui.S)
                })
            }
            addView(error)
        }
        Palette.paint(col)
        val b = MaterialAlertDialogBuilder(a)
            .setTitle(if (s == null) "Add address" else "Edit address")
            .setView(ScrollView(a).apply { addView(col) })
            .setPositiveButton("Save", null)
            .setNegativeButton(android.R.string.cancel, null)
        if (s != null) b.setNeutralButton("Delete") { _, _ -> delete(a, s) }
        val d = b.showImmersive()
        d.getButton(DialogInterface.BUTTON_NEUTRAL)?.setTextColor(Ui.danger(a))
        // Nothing needed is left empty: the dialog stays open with what was typed and says so.
        d.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
            val typed = Address(
                AddressRules.clean(name.text.toString()), AddressRules.clean(org.text.toString()),
                AddressRules.clean(street.text.toString(), AddressRules.MAX_STREET, lines = true), AddressRules.clean(city.text.toString()),
                AddressRules.clean(region.text.toString()), AddressRules.clean(postcode.text.toString()), country,
                AddressRules.clean(tel.text.toString()), AddressRules.clean(email.text.toString()),
            )
            if (!AddressRules.fillable(typed)) {
                error.text = "Add a name, a street and a postcode."
                (if (typed.name.isBlank()) name else if (typed.street.isBlank()) street else postcode).requestFocus()
                return@setOnClickListener
            }
            d.dismiss()
            io.execute {
                Addresses.save(Profiles.currentId, s?.guid, typed)
                later(a) { if (!a.isFinishing) Messages.show(a, "Address saved") }
            }
        }
    }

    private fun delete(a: Activity, s: Addresses.Saved) {
        val profile = Profiles.currentId
        io.execute {
            val row = Addresses.remove(profile, s.guid)
            later(a) {
                Messages.show(a, "Address deleted", action = if (row != null) "Undo" else null) {
                    if (row != null) io.execute { Addresses.putBack(row) }
                }
            }
        }
    }

    /** The phone's country (its SIM's, its network's, or its language's), for a new address. */
    private fun phoneCountry(ctx: Context): String {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        val found = listOf({ tm?.simCountryIso }, { tm?.networkCountryIso }, { java.util.Locale.getDefault().country })
            .firstNotNullOfOrNull { runCatching(it).getOrNull()?.trim()?.takeIf { c -> Countries.isCode(c) } }
        return found?.uppercase(java.util.Locale.ROOT).orEmpty()
    }

    // ------------------------------------------------------------------ after a page's form was sent

    /** The last address asked about, and when: the same form sent twice (or its button and the form) asks once. */
    private var lastAsked: Pair<Address, Long>? = null

    /**
     * The helper's content script saw [tab]'s page send a form with an address (content.js
     * "address-form"): Swift offers to save it, or to update the saved one it adds to, unless one
     * saved says it all already. Never from incognito tabs, with "Offer to save addresses" off, or
     * for what the engine couldn't fill in (no name, street or postcode).
     */
    fun onFormSent(tab: Tab, msg: JSONObject) {
        if (tab.incognito || !SwiftApp.prefs.offerAddresses) return
        val session = tab.session ?: return
        val ctx = SwiftApp.instance ?: return
        val o = msg.optJSONObject("fields") ?: return
        val fields = HashMap<String, String>()
        for (k in o.keys()) if (fields.size < 30) (o.opt(k) as? String)?.let { fields[k.take(20)] = it.take(AddressRules.MAX_STREET) }
        val profile = tab.profileId
        io.execute {
            val sent = AddressRules.fromForm(fields, phoneCountry(ctx)) { Countries.code(it) }
            if (!AddressRules.fillable(sent)) return@execute
            val saved = try {
                Addresses.read(profile).first
            } catch (e: Exception) {
                L.w("Saved addresses couldn't be read for a form (${e.javaClass.simpleName})")
                return@execute
            }
            val decision = AddressRules.decide(sent, saved.map { it.guid to it.address })
            if (decision is AddressRules.Decision.Known) return@execute
            val update = decision as? AddressRules.Decision.Update
            val shown = update?.merged ?: sent
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val now = System.currentTimeMillis()
                lastAsked?.let { (a, at) -> if (a == shown && now - at < ASK_AGAIN_MS) return@post }
                lastAsked = shown to now
                com.swiftbrowser.tabs.SessionFactory.prompts.askAddress(
                    session, update != null, AddressRules.describe(shown) { Countries.name(it) },
                    onSave = {
                        io.execute { Addresses.save(profile, update?.guid, shown) }
                        com.swiftbrowser.tabs.TabManager.ui?.showMessage(if (update != null) "Address updated" else "Address saved")
                    },
                    onNever = {
                        SwiftApp.prefs.offerAddresses = false
                        com.swiftbrowser.tabs.TabManager.ui?.showMessage("Swift won't offer to save addresses. Turn it back on in Settings → Passwords.", long = true)
                    },
                )
            }
        }
    }

    private const val ASK_AGAIN_MS = 60_000L
}
