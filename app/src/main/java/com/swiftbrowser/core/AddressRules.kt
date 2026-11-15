package com.swiftbrowser.core

import java.text.Normalizer
import java.util.Locale

/**
 * 3.0.11: a saved address (Settings → Passwords → Addresses): what the engine fills in on forms
 * (Engine, Prompts) and what Swift offers to keep after a page's form with one was sent
 * (AddressesUi). [country] is the two-letter code ("PK"), as the engine wants it.
 */
data class Address(
    val name: String = "",
    val organization: String = "",
    val street: String = "",
    val city: String = "",
    val region: String = "",
    val postcode: String = "",
    val country: String = "",
    val tel: String = "",
    val email: String = "",
)

/** 3.0.11: the plain logic of saved addresses, tested on the PC (AddressRulesTest). */
object AddressRules {
    /** The longest a field is kept; a street can have a few lines. */
    const val MAX_FIELD = 200
    const val MAX_STREET = 400

    /**
     * Whether the engine can fill [a] in: GeckoView 143 offers an address only when it has a name, a
     * street and a postcode (its Address.isValid), so Swift asks for those three.
     */
    fun fillable(a: Address): Boolean = a.name.isNotBlank() && a.street.isNotBlank() && a.postcode.isNotBlank()

    /**
     * The fields a page's form sent (content.js "address-form": by the names the page gave them, or
     * guessed from their labels) as an address. A form without a country field is taken to be in
     * [defaultCountry]; [countryCode] turns what the form said into a code (null: not a country).
     */
    fun fromForm(fields: Map<String, String>, defaultCountry: String, countryCode: (String) -> String?): Address {
        fun f(key: String) = clean(fields[key].orEmpty(), MAX_FIELD)
        val name = f("name").ifEmpty { listOf(f("given"), f("additional"), f("family")).filter { it.isNotEmpty() }.joinToString(" ") }
        val street = clean(fields["street"].orEmpty(), MAX_STREET, lines = true)
            .ifEmpty { listOf(f("line1"), f("line2"), f("line3")).filter { it.isNotEmpty() }.joinToString("\n") }
        val sentCountry = f("country")
        val country = if (sentCountry.isEmpty()) defaultCountry else countryCode(sentCountry).orEmpty()
        return Address(name, f("org"), street, f("city"), f("region"), f("postcode"), country, f("tel"), f("email"))
    }

    /** [text] trimmed, its spaces single, at most [max] long; [lines]: its lines kept (a street's). */
    fun clean(text: String, max: Int = MAX_FIELD, lines: Boolean = false): String {
        val parts = text.split('\n').map { it.replace(SPACES, " ").trim() }.filter { it.isNotEmpty() }
        return (if (lines) parts.joinToString("\n") else parts.joinToString(" ")).take(max).trim()
    }

    private val SPACES = Regex("[\\s\\u00A0]+")

    /** "Muhammad Ibad Sohail" → "Muhammad Ibad" and "Sohail": the last word is the family name, as forms with two fields want. */
    fun splitName(name: String): Pair<String, String> {
        val words = name.trim().split(SPACES).filter { it.isNotEmpty() }
        if (words.size < 2) return name.trim() to ""
        return words.dropLast(1).joinToString(" ") to words.last()
    }

    /** What to do with an address a form sent, beside the ones saved. */
    sealed class Decision {
        /** One saved already says all of it. */
        data class Known(val guid: String) : Decision()

        /** It adds to a saved one ([guid]): [merged] is that one with what it lacked. */
        data class Update(val guid: String, val merged: Address) : Decision()

        /** A new one. */
        object New : Decision()
    }

    /**
     * [sent] beside the [saved] ones (their ids and addresses). A saved address whose fields all say
     * the same as the form's (or more) knows it; one it only adds to (a phone where there was none, a
     * flat number before the street) is updated, the one it adds least to; otherwise it is new. A
     * field that differs (another street) makes another address, as the engine's own rule has it.
     */
    fun decide(sent: Address, saved: List<Pair<String, Address>>): Decision {
        var best: Pair<String, Address>? = null
        var bestAdds = Int.MAX_VALUE
        for ((guid, s) in saved) {
            val adds = adds(sent, s) ?: continue
            if (adds == 0) return Decision.Known(guid)
            if (adds < bestAdds) {
                best = guid to s
                bestAdds = adds
            }
        }
        return best?.let { Decision.Update(it.first, merge(it.second, sent)) } ?: Decision.New
    }

    /** How many of [sent]'s fields [saved] lacks (or says less of); null when a field they both have differs. */
    fun adds(sent: Address, saved: Address): Int? {
        var n = 0
        for (k in Kind.entries) {
            when (compare(k, k.of(sent), k.of(saved))) {
                Cmp.SAME -> Unit
                Cmp.MORE -> n++
                Cmp.DIFFERENT -> return null
            }
        }
        return n
    }

    /** [saved] with what [sent] adds to it. */
    fun merge(saved: Address, sent: Address): Address {
        fun pick(k: Kind) = if (compare(k, k.of(sent), k.of(saved)) == Cmp.MORE) k.of(sent) else k.of(saved)
        return Address(
            pick(Kind.NAME), pick(Kind.ORG), pick(Kind.STREET), pick(Kind.CITY), pick(Kind.REGION),
            pick(Kind.POSTCODE), pick(Kind.COUNTRY), pick(Kind.TEL), pick(Kind.EMAIL),
        )
    }

    private enum class Cmp { SAME, MORE, DIFFERENT }

    /** The fields, and how each is compared. */
    private enum class Kind(val of: (Address) -> String) {
        NAME(Address::name), ORG(Address::organization), STREET(Address::street), CITY(Address::city),
        REGION(Address::region), POSTCODE(Address::postcode), COUNTRY(Address::country), TEL(Address::tel), EMAIL(Address::email),
    }

    /** [sent]'s value of field [k] beside [saved]'s: the same (or less), more, or different. */
    private fun compare(k: Kind, sent: String, saved: String): Cmp {
        if (sent.isBlank()) return Cmp.SAME
        if (saved.isBlank()) return Cmp.MORE
        return when (k) {
            // A name or street with words left out is the same one ("Muhammad Sohail" of "Muhammad Ibad
            // Sohail"); with words added it says more ("Flat 3, 12 Main St" of "12 Main St").
            Kind.NAME, Kind.STREET -> {
                val a = words(sent)
                val b = words(saved)
                when {
                    a == b || b.containsAll(a) -> Cmp.SAME
                    a.containsAll(b) -> Cmp.MORE
                    else -> Cmp.DIFFERENT
                }
            }
            Kind.POSTCODE -> if (sent.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() } == saved.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }) Cmp.SAME else Cmp.DIFFERENT
            Kind.TEL -> if (samePhone(sent, saved)) Cmp.SAME else Cmp.DIFFERENT
            Kind.COUNTRY, Kind.EMAIL -> if (sent.trim().equals(saved.trim(), ignoreCase = true)) Cmp.SAME else Cmp.DIFFERENT
            else -> if (words(sent) == words(saved)) Cmp.SAME else Cmp.DIFFERENT
        }
    }

    /** A text's words, in small letters, without accents and punctuation (each word once). */
    private fun words(text: String): Set<String> =
        plain(text).split(' ').filter { it.isNotEmpty() }.toSet()

    /** [text] in small letters without accents, anything but letters and figures a single space. */
    fun plain(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "").lowercase(Locale.ROOT).replace(NOT_WORD, " ").trim()

    private val MARKS = Regex("\\p{M}+")
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

    /**
     * The same phone number, written either way: "+92 300 1234567" and "0300-1234567" (the country's
     * code or the leading 0 left out); at least 7 figures must match.
     */
    fun samePhone(a: String, b: String): Boolean {
        val x = a.filter { it.isDigit() }.trimStart('0')
        val y = b.filter { it.isDigit() }.trimStart('0')
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        val (short, long) = if (x.length <= y.length) x to y else y to x
        return short.length >= 7 && long.endsWith(short)
    }

    /** One line about [a] under its name in a list: its street's first line, the city and the postcode. */
    fun summary(a: Address): String =
        listOf(a.street.lineSequence().firstOrNull().orEmpty(), a.city, a.postcode).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")

    /** [a] as it is written on an envelope, for a question about it; [countryName] names its country. */
    fun describe(a: Address, countryName: (String) -> String): String {
        val place = listOf(listOf(a.city, a.region).filter { it.isNotBlank() }.joinToString(", "), a.postcode).filter { it.isNotBlank() }.joinToString(" ")
        val reach = listOf(a.tel, a.email).filter { it.isNotBlank() }.joinToString(" · ")
        return (listOf(a.name, a.organization) + a.street.lines() + listOf(place, if (a.country.isBlank()) "" else countryName(a.country), reach))
            .map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }
}

/** 3.0.11: countries by their two-letter codes, as forms and the engine write them (tested on the PC). */
object Countries {
    private val CODES: List<String> by lazy { Locale.getISOCountries().toList() }

    /** Names forms use that aren't a country's own name in any language. */
    private val OTHER_NAMES = mapOf(
        "usa" to "US", "u s a" to "US", "united states of america" to "US", "america" to "US",
        "uk" to "GB", "u k" to "GB", "great britain" to "GB", "britain" to "GB", "england" to "GB", "scotland" to "GB", "wales" to "GB",
        "uae" to "AE", "u a e" to "AE", "emirates" to "AE", "ksa" to "SA", "south korea" to "KR", "korea" to "KR",
        "russia" to "RU", "vietnam" to "VN", "iran" to "IR", "syria" to "SY", "turkey" to "TR", "turkiye" to "TR",
        "holland" to "NL", "czech republic" to "CZ", "ivory coast" to "CI", "burma" to "MM",
    )

    /** Whether [code] is a country's two-letter code. */
    fun isCode(code: String): Boolean = code.length == 2 && code.uppercase(Locale.ROOT) in CODES

    /**
     * The code of the country a form gave: its code ("pk", "PAK"), or its name in English or in one of
     * [locales] ("Pakistan", "Deutschland"); null when it isn't one Swift knows.
     */
    fun code(text: String, locales: List<Locale> = listOf(Locale.getDefault())): String? {
        val t = text.trim()
        if (isCode(t)) return t.uppercase(Locale.ROOT)
        val key = AddressRules.plain(t)
        if (key.isEmpty()) return null
        OTHER_NAMES[key]?.let { return it }
        val languages = (listOf(Locale.ENGLISH) + locales).distinct()
        for (code in CODES) {
            val country = Locale("", code)
            if (t.length == 3 && runCatching { country.isO3Country }.getOrNull().equals(t, ignoreCase = true)) return code
            for (l in languages) if (AddressRules.plain(country.getDisplayCountry(l)) == key) return code
        }
        return null
    }

    /** [code]'s name in [locale] ("Pakistan"); the code itself when it isn't one. */
    fun name(code: String, locale: Locale = Locale.getDefault()): String =
        if (isCode(code)) Locale("", code.uppercase(Locale.ROOT)).getDisplayCountry(locale).ifBlank { code } else code

    /** Every country's code and name in [locale], by name. */
    fun all(locale: Locale = Locale.getDefault()): List<Pair<String, String>> =
        CODES.map { it to name(it, locale) }.sortedWith(compareBy(java.text.Collator.getInstance(locale)) { it.second })
}
