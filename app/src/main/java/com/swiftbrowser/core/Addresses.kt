package com.swiftbrowser.core

import com.swiftbrowser.SwiftApp
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.Autocomplete
import java.util.UUID

/**
 * 3.0.11: saved addresses (Settings → Passwords → Addresses): the engine fills them in on forms
 * (Engine's onAddressFetch, Prompts' pick sheet), and Swift offers to keep one after a page's form
 * with it was sent (AddressesUi). Each profile has its own. Each is kept as its fields' JSON, locked
 * with the keystore key the saved passwords use (Passwords.lock); like them, they go into backups
 * only inside the part locked with the backup password (BackupSecrets). Never from incognito tabs.
 */
object Addresses {
    /** A saved address and its id. */
    class Saved(val guid: String, val address: Address, val created: Long)

    /**
     * Blocking: [profileId]'s addresses that can be read, and the rows of those that can't any more
     * (the keystore lost its key). Throws when the keystore doesn't answer (they may be fine).
     */
    fun read(profileId: Long): Pair<List<Saved>, List<AddressRow>> {
        val rows = SwiftApp.db.addresses(profileId)
        val plain = Passwords.unlockAll(rows.map { it.data })
        val good = ArrayList<Saved>()
        val bad = ArrayList<AddressRow>()
        rows.forEachIndexed { i, r ->
            val a = plain[i]?.let { fromJson(it) }
            if (a != null) good += Saved(r.guid, a, r.created) else bad += r
        }
        return good to bad
    }

    /** Blocking: saves [a] in [profileId], as a new address or in place of [guid]'s; returns its id. */
    fun save(profileId: Long, guid: String?, a: Address): String {
        val db = SwiftApp.db
        val now = System.currentTimeMillis()
        val old = guid?.let { g -> db.addresses(profileId).firstOrNull { it.guid == g } }
        val id = old?.guid ?: UUID.randomUUID().toString()
        db.saveAddress(AddressRow(id, profileId, Passwords.lock(toJson(a).toString()), old?.created ?: now, now))
        return id
    }

    /** Blocking. Deletes an address and gives back what was stored, for Undo. */
    fun remove(profileId: Long, guid: String): AddressRow? {
        val db = SwiftApp.db
        val row = db.addresses(profileId).firstOrNull { it.guid == guid }
        db.deleteAddress(guid)
        return row
    }

    fun putBack(row: AddressRow) = SwiftApp.db.saveAddress(row)

    /** Blocking: deletes addresses that can't be read any more. */
    fun removeRows(rows: List<AddressRow>) {
        for (r in rows) SwiftApp.db.deleteAddress(r.guid)
    }

    /**
     * Blocking. The engine's request: the current profile's addresses, as it fills them in. The name
     * goes in its parts too (a form with first and last name fields), and the street with its lines.
     */
    fun entries(): Array<Autocomplete.Address> = read(Profiles.currentId).first.filter { AddressRules.fillable(it.address) }.map { s ->
        val a = s.address
        val (given, family) = AddressRules.splitName(a.name)
        Autocomplete.Address.Builder()
            .guid(s.guid).name(a.name).givenName(given).additionalName("").familyName(family)
            .organization(a.organization).streetAddress(a.street).addressLevel1(a.region).addressLevel2(a.city).addressLevel3("")
            .postalCode(a.postcode).country(a.country).tel(a.tel).email(a.email)
            .build()
    }.toTypedArray()

    // ------------------------------------------------------------------ as JSON (kept locked, and in locked backups)

    fun toJson(a: Address): JSONObject = JSONObject()
        .put("name", a.name).put("org", a.organization).put("street", a.street).put("city", a.city).put("region", a.region)
        .put("postcode", a.postcode).put("country", a.country).put("tel", a.tel).put("email", a.email)

    /** Null when it isn't one (each field kept as long as Swift keeps them). */
    fun fromJson(text: String): Address? = try {
        fromJson(JSONObject(text))
    } catch (_: Exception) {
        null
    }

    private fun fromJson(o: JSONObject): Address {
        fun f(key: String, max: Int = AddressRules.MAX_FIELD) = AddressRules.clean((o.opt(key) as? String).orEmpty(), max, lines = key == "street")
        return Address(
            f("name"), f("org"), f("street", AddressRules.MAX_STREET), f("city"), f("region"), f("postcode"),
            f("country", 2).uppercase(java.util.Locale.ROOT).takeIf { Countries.isCode(it) }.orEmpty(), f("tel"), f("email"),
        )
    }

    /** Blocking: every profile's addresses for a locked backup ({"p", "guid", "a", "created"}); unreadable ones left out. */
    fun forBackup(): JSONArray {
        val out = JSONArray()
        for (p in Profiles.all()) {
            // As the passwords: the keystore not answering leaves them out of this backup, not the backup out.
            val saved = try {
                read(p.id).first
            } catch (e: Exception) {
                L.w("Saved addresses left out of the backup (${e.javaClass.simpleName})")
                emptyList()
            }
            for (s in saved) out.put(JSONObject().put("p", p.id).put("guid", s.guid).put("a", toJson(s.address)).put("created", s.created))
        }
        return out
    }

    /**
     * Blocking: the addresses of a locked backup's [list] put back into the profiles they have here
     * ([ids]: the backup's profile id → this phone's), locked with this phone's key; one the profile
     * knows already is left out. Returns how many were added.
     */
    fun restore(list: JSONArray, ids: Map<Long, Long>): Int {
        val db = SwiftApp.db
        val known = HashMap<Long, MutableList<Address>>()
        var added = 0
        for (i in 0 until minOf(list.length(), MAX_RESTORED)) {
            val o = list.optJSONObject(i) ?: continue
            val pid = ids[o.optLong("p")] ?: continue
            val a = o.optJSONObject("a")?.let { fromJson(it) }?.takeIf { AddressRules.fillable(it) } ?: continue
            val here = known.getOrPut(pid) { read(pid).first.mapTo(ArrayList()) { it.address } }
            if (AddressRules.decide(a, here.mapIndexed { n, h -> "$n" to h }) is AddressRules.Decision.Known) continue
            // An id in use in any profile gets a new one: saving over it would move that address here.
            val guid = o.optString("guid").takeIf { g -> g.isNotBlank() && g.length <= 64 && !db.addressExists(g) } ?: UUID.randomUUID().toString()
            val created = o.optLong("created", System.currentTimeMillis())
            db.saveAddress(AddressRow(guid, pid, Passwords.lock(toJson(a).toString()), created, created))
            here += a
            added++
        }
        return added
    }

    /** More would be a backup made by mistake (or by someone else). */
    private const val MAX_RESTORED = 500
}
