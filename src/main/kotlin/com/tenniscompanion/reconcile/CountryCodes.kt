package com.tenniscompanion.reconcile

/**
 * Maps a full English country name (as api-tennis.com returns, e.g. "Serbia") to the **IOC** 3-letter
 * code that the Sackmann player data uses (e.g. Germany → GER, not the ISO "DEU"; Switzerland → SUI).
 * IOC ≠ ISO for several tennis nations, so a curated map is more correct than a Locale-based ISO
 * lookup. Reconciliation uses country only as a Tier-2 tiebreaker, so unknown names returning null is
 * acceptable — they just don't earn the country signal. Returns null for null/blank/unknown input.
 */
object CountryCodes {

    private val byName: Map<String, String> = buildMap {
        fun add(ioc: String, vararg names: String) = names.forEach { put(it.lowercase(), ioc) }

        add("SRB", "Serbia")
        add("ESP", "Spain")
        add("ITA", "Italy")
        add("SUI", "Switzerland")
        add("GER", "Germany")
        add("RUS", "Russia", "Russian Federation")
        add("USA", "United States", "United States of America", "Usa")
        add("GBR", "Great Britain", "United Kingdom", "Britain")
        add("FRA", "France")
        add("GRE", "Greece")
        add("NOR", "Norway")
        add("DEN", "Denmark")
        add("AUS", "Australia")
        add("CAN", "Canada")
        add("POL", "Poland")
        add("BLR", "Belarus")
        add("JPN", "Japan")
        add("CHN", "China", "China PR")
        add("NED", "Netherlands")
        add("AUT", "Austria")
        add("ARG", "Argentina")
        add("CRO", "Croatia")
        add("BUL", "Bulgaria")
        add("CZE", "Czech Republic", "Czechia")
        add("KAZ", "Kazakhstan")
        add("CHI", "Chile")
        add("BRA", "Brazil")
        add("HUN", "Hungary")
        add("BEL", "Belgium")
        add("RSA", "South Africa")
        add("FIN", "Finland")
        add("SWE", "Sweden")
        add("POR", "Portugal")
        add("SVK", "Slovakia")
        add("SLO", "Slovenia")
        add("ROU", "Romania", "Roumania")
        add("UKR", "Ukraine")
        add("MDA", "Moldova")
        add("BIH", "Bosnia and Herzegovina", "Bosnia")
        add("GEO", "Georgia")
        add("IND", "India")
        add("TUN", "Tunisia")
        add("EGY", "Egypt")
        add("COL", "Colombia")
        add("PER", "Peru")
        add("URU", "Uruguay")
        add("MEX", "Mexico")
        add("ECU", "Ecuador")
        add("LAT", "Latvia")
        add("LTU", "Lithuania")
        add("EST", "Estonia")
        add("NZL", "New Zealand")
        add("TPE", "Chinese Taipei", "Taiwan", "Taipei")
        add("KOR", "South Korea", "Korea", "Korea Republic")
        add("THA", "Thailand")
        add("INA", "Indonesia")
        add("ISR", "Israel")
        add("TUR", "Turkey", "Türkiye", "Turkiye")
        add("CYP", "Cyprus")
        add("MON", "Monaco")
        add("LUX", "Luxembourg")
        add("IRL", "Ireland")
        add("DOM", "Dominican Republic")
        add("VEN", "Venezuela")
        add("BOL", "Bolivia")
        add("PAR", "Paraguay")
        add("CRC", "Costa Rica")
        add("ZIM", "Zimbabwe")
        add("KSA", "Saudi Arabia")
        add("UZB", "Uzbekistan")
        add("PHI", "Philippines")
    }

    fun toIoc(name: String?): String? = name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { byName[it] }
}
