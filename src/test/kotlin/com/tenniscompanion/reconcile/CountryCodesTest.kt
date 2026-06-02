package com.tenniscompanion.reconcile

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CountryCodesTest {

    @Test
    fun `maps tennis nations to IOC codes, not ISO`() {
        assertEquals("SRB", CountryCodes.toIoc("Serbia"))
        assertEquals("GER", CountryCodes.toIoc("Germany")) // IOC GER, not ISO DEU
        assertEquals("SUI", CountryCodes.toIoc("Switzerland")) // IOC SUI, not ISO CHE
        assertEquals("NED", CountryCodes.toIoc("Netherlands")) // IOC NED, not ISO NLD
        assertEquals("USA", CountryCodes.toIoc("United States"))
    }

    @Test
    fun `is case and whitespace insensitive`() {
        assertEquals("ESP", CountryCodes.toIoc("  spain "))
        assertEquals("CZE", CountryCodes.toIoc("Czechia"))
    }

    @Test
    fun `returns null for null, blank, or unknown`() {
        assertNull(CountryCodes.toIoc(null))
        assertNull(CountryCodes.toIoc(""))
        assertNull(CountryCodes.toIoc("Atlantis"))
    }
}
