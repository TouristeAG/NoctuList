package com.eventmanager.app.portal.certificate

import com.eventmanager.app.data.utils.AppTimeZone
import java.time.Instant

/** Days are integers in the form YYYYMMDD, taken in the association time zone (Europe/Zurich). */
object CertificateDates {

    private val months = mapOf(
        "fr" to listOf("janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre"),
        "en" to listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"),
        "de" to listOf("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember"),
        "it" to listOf("gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre"),
        "es" to listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"),
    )

    fun dayOf(millis: Long): Int {
        val date = Instant.ofEpochMilli(millis).atZone(AppTimeZone.zoneId).toLocalDate()
        return date.year * 10000 + date.monthValue * 100 + date.dayOfMonth
    }

    private fun lang(lang: String) = if (months.containsKey(lang)) lang else "en"
    private fun year(day: Int) = day / 10000
    private fun month(day: Int, lang: String) = months.getValue(lang)[(day / 100) % 100 - 1]

    private fun withYear(lang: String, month: String, year: Int) =
        if (lang == "es") "$month de $year" else "$month $year"

    fun longDate(day: Int, lang: String): String {
        val l = lang(lang)
        val d = day % 100
        val m = month(day, l)
        val y = year(day)
        return when (l) {
            "fr" -> "${if (d == 1) "1er" else d.toString()} $m $y"
            "de" -> "$d. $m $y"
            "es" -> "$d de $m de $y"
            "it" -> "${if (d == 1) "1°" else d.toString()} $m $y"
            else -> "$d $m $y"
        }
    }

    fun monthYear(day: Int, lang: String): String {
        val l = lang(lang)
        return withYear(l, month(day, l), year(day))
    }

    fun period(firstDay: Int, lastDay: Int, lang: String): String {
        val l = lang(lang)
        val sameYear = year(firstDay) == year(lastDay)
        val sameMonth = sameYear && (firstDay / 100) % 100 == (lastDay / 100) % 100
        val first = month(firstDay, l)
        val last = month(lastDay, l)
        if (sameMonth) {
            val my = withYear(l, first, year(firstDay))
            return when (l) {
                "fr", "es" -> "en $my"
                "en" -> "in $my"
                "de" -> "im $my"
                else -> "a $my"
            }
        }
        val (between, and) = when (l) {
            "fr" -> "entre" to "et"
            "en" -> "between" to "and"
            "de" -> "zwischen" to "und"
            "it" -> "tra" to "e"
            else -> "entre" to "y"
        }
        return if (sameYear) {
            "$between $first $and ${withYear(l, last, year(lastDay))}"
        } else {
            "$between ${withYear(l, first, year(firstDay))} $and ${withYear(l, last, year(lastDay))}"
        }
    }
}
