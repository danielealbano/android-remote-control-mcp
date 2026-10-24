package com.danielealbano.androidremotecontrolmcp.geo

import java.util.Locale

/**
 * Turns a 2-letter ISO-3166 country code into display pieces with no bundled assets: the flag as a
 * regional-indicator emoji pair, and the localized country name via [Locale]. Both return null for a
 * missing or non-country code (e.g. "EU", "AP", empty), so the UI can fall back gracefully.
 */
object CountryDisplay {
    private const val REGIONAL_INDICATOR_A = 0x1F1E6
    private const val ALPHA_A = 'A'.code
    private const val CODE_LEN = 2

    fun flag(code: String?): String? {
        val c = normalize(code) ?: return null
        val first = REGIONAL_INDICATOR_A + (c[0].code - ALPHA_A)
        val second = REGIONAL_INDICATOR_A + (c[1].code - ALPHA_A)
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }

    fun name(code: String?): String? {
        val c = normalize(code) ?: return null
        val name =
            Locale
                .Builder()
                .setRegion(c)
                .build()
                .getDisplayCountry(Locale.getDefault())
        // getDisplayCountry echoes the code back when it is not a known region.
        return name.takeIf { it.isNotEmpty() && !it.equals(c, ignoreCase = true) }
    }

    private fun normalize(code: String?): String? =
        code?.trim()?.uppercase()?.takeIf { it.length == CODE_LEN && it.all { ch -> ch in 'A'..'Z' } }
}
