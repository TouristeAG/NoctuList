package com.eventmanager.app.ui.components.settings.certificate

import com.eventmanager.app.portal.certificate.CertJob
import com.eventmanager.app.portal.certificate.CertJobType
import com.eventmanager.app.portal.certificate.CertPerson
import com.eventmanager.app.portal.certificate.CertificateCategory

/**
 * The made-up volunteer of the certificate preview: Léa Exemple, with one mandate, eight shifts in
 * two roles and one meeting, all in 2025 so every day is in the past.
 */
object CertificateSampleData {
    private class Names(
        val mandate: String,
        val mandateDescription: String,
        val bar: String,
        val barDescription: String,
        val entrance: String,
        val entranceDescription: String,
        val meeting: String,
    )

    private val names = mapOf(
        "fr" to Names(
            "Trésorière", "Tenue des comptes et suivi du budget de l'association.",
            "Bar", "Service au bar et tenue de la caisse.",
            "Accueil", "Accueil du public et contrôle des billets.",
            "Assemblée générale",
        ),
        "en" to Names(
            "Treasurer", "Kept the accounts and followed the association budget.",
            "Bar", "Served at the bar and ran the till.",
            "Front desk", "Welcomed the public and checked tickets.",
            "General meeting",
        ),
        "de" to Names(
            "Kassiererin", "Buchhaltung und Budgetkontrolle des Vereins.",
            "Bar", "Ausschank an der Bar und Kassenführung.",
            "Empfang", "Empfang der Gäste und Ticketkontrolle.",
            "Generalversammlung",
        ),
        "it" to Names(
            "Tesoriera", "Tenuta della contabilità e controllo del bilancio dell'associazione.",
            "Bar", "Servizio al bar e gestione della cassa.",
            "Accoglienza", "Accoglienza del pubblico e controllo dei biglietti.",
            "Assemblea generale",
        ),
        "es" to Names(
            "Tesorera", "Llevó las cuentas y el seguimiento del presupuesto de la asociación.",
            "Barra", "Servicio en la barra y gestión de la caja.",
            "Recepción", "Recepción del público y control de entradas.",
            "Asamblea general",
        ),
    )

    const val NAME = "Léa Exemple"

    fun sample(language: String): Triple<CertPerson, List<CertJobType>, List<CertJob>> {
        val n = names[language] ?: names.getValue("en")
        val types = listOf(
            CertJobType(n.mandate, CertificateCategory.MANDATE, n.mandateDescription),
            CertJobType(n.bar, CertificateCategory.SHIFT, n.barDescription),
            CertJobType(n.entrance, CertificateCategory.SHIFT, n.entranceDescription),
            CertJobType(n.meeting, CertificateCategory.MEETING, ""),
        )
        val jobs = buildList {
            add(CertJob(n.mandate, 20250301))
            add(CertJob(n.mandate, 20251015))
            for (day in listOf(20250315, 20250412, 20250517, 20250621, 20250913)) add(CertJob(n.bar, day))
            for (day in listOf(20250329, 20250705, 20251011)) add(CertJob(n.entrance, day))
            add(CertJob(n.meeting, 20250610))
        }
        return Triple(CertPerson("Léa", "Exemple", "FEMALE"), types, jobs)
    }
}
