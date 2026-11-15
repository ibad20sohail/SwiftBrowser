package com.swiftbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** 3.0.11: saved addresses: what a form sent, and whether Swift asks to save or update one. */
class AddressRulesTest {
    private val home = Address(
        name = "Muhammad Ibad Sohail", street = "House 12, Street 4\nF-7/2", city = "Islamabad", region = "ICT",
        postcode = "44000", country = "PK", tel = "+92 300 1234567", email = "ibad@example.com",
    )

    private fun code(text: String) = Countries.code(text, listOf(Locale.ENGLISH))

    @Test
    fun aFormsFieldsMakeOneAddress() {
        val sent = AddressRules.fromForm(
            mapOf(
                "given" to "  Muhammad   Ibad ", "family" to "Sohail", "line1" to "House 12, Street 4", "line2" to "F-7/2",
                "city" to "Islamabad", "postcode" to "44000", "country" to "Pakistan", "tel" to "0300-1234567",
            ),
            defaultCountry = "US", countryCode = { code(it) },
        )
        assertEquals("Muhammad Ibad Sohail", sent.name)
        assertEquals("House 12, Street 4\nF-7/2", sent.street)
        assertEquals("PK", sent.country)
        assertTrue(AddressRules.fillable(sent))
        // No country field: the phone's own; one Swift can't read: none rather than a wrong one.
        assertEquals("US", AddressRules.fromForm(mapOf("street" to "1 Main St"), "US") { code(it) }.country)
        assertEquals("", AddressRules.fromForm(mapOf("country" to "Atlantis"), "US") { code(it) }.country)
    }

    @Test
    fun onlyAddressesTheEngineCanFillAreKept() {
        assertTrue(AddressRules.fillable(home))
        assertFalse(AddressRules.fillable(home.copy(postcode = " ")))
        assertFalse(AddressRules.fillable(home.copy(name = "")))
        assertFalse(AddressRules.fillable(home.copy(street = "")))
    }

    @Test
    fun theSameAddressWrittenAnotherWayIsKnown() {
        val sent = home.copy(name = "muhammad sohail", street = "House 12 Street 4, F-7/2", tel = "03001234567", email = "IBAD@example.com", region = "")
        assertEquals(AddressRules.Decision.Known("a"), AddressRules.decide(sent, listOf("a" to home)))
    }

    @Test
    fun anAddressThatAddsToOneSavedUpdatesIt() {
        val saved = home.copy(tel = "", organization = "")
        val sent = home.copy(organization = "Swift Ltd", street = "Flat 3, House 12, Street 4\nF-7/2")
        val d = AddressRules.decide(sent, listOf("x" to home.copy(street = "Other road 1"), "a" to saved))
        assertTrue(d is AddressRules.Decision.Update)
        d as AddressRules.Decision.Update
        assertEquals("a", d.guid)
        assertEquals("Swift Ltd", d.merged.organization)
        assertEquals("Flat 3, House 12, Street 4\nF-7/2", d.merged.street)
        assertEquals("+92 300 1234567", d.merged.tel)
        // What the form left out stays as saved.
        assertEquals("ICT", d.merged.region)
    }

    @Test
    fun aDifferentStreetOrPhoneIsAnotherAddress() {
        assertEquals(AddressRules.Decision.New, AddressRules.decide(home.copy(street = "House 21, Street 4\nF-7/2"), listOf("a" to home)))
        assertEquals(AddressRules.Decision.New, AddressRules.decide(home.copy(tel = "0321 7654321"), listOf("a" to home)))
        assertEquals(AddressRules.Decision.New, AddressRules.decide(home, emptyList()))
    }

    @Test
    fun phonesMatchWithOrWithoutTheCountrysCode() {
        assertTrue(AddressRules.samePhone("+92 300 1234567", "0300-1234567"))
        assertTrue(AddressRules.samePhone("(555) 123-4567", "+1 555 123 4567"))
        assertFalse(AddressRules.samePhone("1234567", "7654321"))
        assertFalse(AddressRules.samePhone("4567", "1234567"))
        assertFalse(AddressRules.samePhone("", ""))
    }

    @Test
    fun theLastWordIsTheFamilyName() {
        assertEquals("Muhammad Ibad" to "Sohail", AddressRules.splitName("Muhammad  Ibad Sohail "))
        assertEquals("Cher" to "", AddressRules.splitName("Cher"))
    }

    @Test
    fun countriesByCodeOrName() {
        assertEquals("PK", code("pk"))
        assertEquals("PK", code("PAK"))
        assertEquals("PK", code(" Pakistan "))
        assertEquals("US", code("USA"))
        assertEquals("US", code("United States"))
        assertEquals("GB", code("U.K."))
        assertEquals("DE", Countries.code("Deutschland", listOf(Locale.GERMAN)))
        assertEquals("CI", code("Cote d'Ivoire"))
        assertEquals("CI", code("Ivory Coast"))
        assertNull(code("Atlantis"))
        assertNull(code(""))
        assertEquals("Pakistan", Countries.name("PK", Locale.ENGLISH))
    }

    @Test
    fun anAddressReadsAsOnAnEnvelope() {
        assertEquals(
            "Muhammad Ibad Sohail\nHouse 12, Street 4\nF-7/2\nIslamabad, ICT 44000\nPakistan\n+92 300 1234567 · ibad@example.com",
            AddressRules.describe(home) { Countries.name(it, Locale.ENGLISH) },
        )
        assertEquals("House 12, Street 4, Islamabad, 44000", AddressRules.summary(home))
    }
}
