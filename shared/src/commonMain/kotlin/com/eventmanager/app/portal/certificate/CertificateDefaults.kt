package com.eventmanager.app.portal.certificate

/**
 * Default texts of the volunteer certificate, per language.
 *
 * Sentences are written as small templates: `{name}` is a variable, `[a|b|c]` is a gender
 * agreement (masculine, feminine, non-binary), anything else is plain text.
 */
object CertificateDefaults {
    val LANGUAGES = listOf("fr", "en", "de", "it", "es")

    private class Texts(
        val title: String,
        val introOne: String,
        val introMany: String,
        val mandate: String,
        val shiftOne: String,
        val shiftMany: String,
        val shiftManyOneRole: String,
        val photoOne: String,
        val photoMany: String,
        val graphicEventOne: String,
        val graphicEventMany: String,
        val graphicAssociation: String,
        val otherOne: String,
        val otherMany: String,
        val otherManyOneRole: String,
        val meetingOne: String,
        val meetingMany: String,
        val closing: String,
        val dateLine: String,
        val mailSubject: String,
        val mailBody: String,
    )

    private val texts: Map<String, Texts> = mapOf(
        "fr" to Texts(
            title = "Attestation de bénévolat",
            introOne = "{association} atteste que {firstName} {lastName} s'est [engagé|engagée|engagéx] bénévolement au sein de l'association.",
            introMany = "{association} atteste que {firstName} {lastName} s'est [engagé|engagée|engagéx] bénévolement au sein de l'association {period}.",
            mandate = "{firstName} a exercé le mandat de {role} {period}.",
            shiftOne = "Le {date}, {firstName} a participé bénévolement à un événement organisé par {association} en tant que {role}.",
            shiftMany = "{firstName} a participé bénévolement à {count} événements organisés par {association} {period}, dans les rôles suivants :",
            shiftManyOneRole = "{firstName} a participé bénévolement à {count} événements organisés par {association} {period}, en tant que {role}.",
            photoOne = "Le {date}, {firstName} a assuré la couverture photo et vidéo d'un événement organisé par {association}.",
            photoMany = "{firstName} a assuré la couverture photo et vidéo de {count} événements organisés par {association} {period}.",
            graphicEventOne = "Le {date}, {firstName} a réalisé les visuels d'un événement organisé par {association}.",
            graphicEventMany = "{firstName} a réalisé les visuels de {count} événements organisés par {association} {period}.",
            graphicAssociation = "{firstName} a contribué à l'identité graphique de l'association {period}.",
            otherOne = "Le {date}, {firstName} a mené une mission pour {association} en tant que {role}.",
            otherMany = "{firstName} a mené {count} missions pour {association} {period}, dans les rôles suivants :",
            otherManyOneRole = "{firstName} a mené {count} missions pour {association} {period}, en tant que {role}.",
            meetingOne = "Le {date}, {firstName} a participé à une réunion organisée par {association}.",
            meetingMany = "{firstName} a participé à {count} réunions organisées par {association} {period}.",
            closing = "Nous remercions chaleureusement {firstName} pour son engagement.",
            dateLine = "Fait à {place}, le {today}",
            mailSubject = "Ton attestation de bénévolat",
            mailBody = "Bonjour {firstName}, voici ton attestation de bénévolat pour {association}. Elle est jointe à cet e-mail.",
        ),
        "en" to Texts(
            title = "Volunteer certificate",
            introOne = "{association} certifies that {firstName} {lastName} has volunteered with the association.",
            introMany = "{association} certifies that {firstName} {lastName} has volunteered with the association {period}.",
            mandate = "{firstName} held the role of {role} {period}.",
            shiftOne = "On {date}, {firstName} volunteered at an event organised by {association} as {role}.",
            shiftMany = "{firstName} volunteered at {count} events organised by {association} {period}, in the following roles:",
            shiftManyOneRole = "{firstName} volunteered at {count} events organised by {association} {period}, as {role}.",
            photoOne = "On {date}, {firstName} provided photo and video coverage of an event organised by {association}.",
            photoMany = "{firstName} provided photo and video coverage of {count} events organised by {association} {period}.",
            graphicEventOne = "On {date}, {firstName} designed the visuals for an event organised by {association}.",
            graphicEventMany = "{firstName} designed the visuals for {count} events organised by {association} {period}.",
            graphicAssociation = "{firstName} contributed to the association's visual identity {period}.",
            otherOne = "On {date}, {firstName} carried out a task for {association} as {role}.",
            otherMany = "{firstName} carried out {count} tasks for {association} {period}, in the following roles:",
            otherManyOneRole = "{firstName} carried out {count} tasks for {association} {period}, as {role}.",
            meetingOne = "On {date}, {firstName} took part in a meeting organised by {association}.",
            meetingMany = "{firstName} took part in {count} meetings organised by {association} {period}.",
            closing = "We warmly thank {firstName} for their commitment.",
            dateLine = "Issued in {place}, on {today}",
            mailSubject = "Your volunteer certificate",
            mailBody = "Hello {firstName}, here is your volunteer certificate for {association}. It is attached to this e-mail.",
        ),
        "de" to Texts(
            title = "Bestätigung über ehrenamtliches Engagement",
            introOne = "{association} bestätigt, dass sich {firstName} {lastName} ehrenamtlich im Verein engagiert hat.",
            introMany = "{association} bestätigt, dass sich {firstName} {lastName} {period} ehrenamtlich im Verein engagiert hat.",
            mandate = "{firstName} hat {period} das Amt als {role} ausgeübt.",
            shiftOne = "Am {date} hat sich {firstName} ehrenamtlich bei einer Veranstaltung von {association} als {role} engagiert.",
            shiftMany = "{firstName} hat sich {period} ehrenamtlich bei {count} Veranstaltungen von {association} engagiert, und zwar in folgenden Funktionen:",
            shiftManyOneRole = "{firstName} hat sich {period} ehrenamtlich bei {count} Veranstaltungen von {association} engagiert, und zwar als {role}.",
            photoOne = "Am {date} hat {firstName} eine Veranstaltung von {association} fotografisch und filmisch begleitet.",
            photoMany = "{firstName} hat {period} {count} Veranstaltungen von {association} fotografisch und filmisch begleitet.",
            graphicEventOne = "Am {date} hat {firstName} die Grafiken für eine Veranstaltung von {association} gestaltet.",
            graphicEventMany = "{firstName} hat {period} die Grafiken für {count} Veranstaltungen von {association} gestaltet.",
            graphicAssociation = "{firstName} hat {period} zum grafischen Auftritt von {association} beigetragen.",
            otherOne = "Am {date} hat {firstName} für {association} eine Aufgabe als {role} übernommen.",
            otherMany = "{firstName} hat {period} für {association} {count} Aufgaben übernommen, und zwar in folgenden Funktionen:",
            otherManyOneRole = "{firstName} hat {period} für {association} {count} Aufgaben übernommen, und zwar als {role}.",
            meetingOne = "Am {date} hat {firstName} an einer Sitzung von {association} teilgenommen.",
            meetingMany = "{firstName} hat {period} an {count} Sitzungen von {association} teilgenommen.",
            closing = "Wir danken {firstName} herzlich für das Engagement.",
            dateLine = "Ausgestellt in {place}, am {today}",
            mailSubject = "Deine Bestätigung über ehrenamtliches Engagement",
            mailBody = "Hallo {firstName}, hier ist deine Bestätigung über dein ehrenamtliches Engagement bei {association}. Sie ist dieser E-Mail angehängt.",
        ),
        "it" to Texts(
            title = "Attestazione di volontariato",
            introOne = "{association} attesta che {firstName} {lastName} ha svolto attività di volontariato nell'associazione.",
            introMany = "{association} attesta che {firstName} {lastName} ha svolto attività di volontariato nell'associazione {period}.",
            mandate = "{firstName} ha ricoperto l'incarico di {role} {period}.",
            shiftOne = "In data {date}, {firstName} ha svolto attività di volontariato a un evento organizzato da {association} come {role}.",
            shiftMany = "{firstName} ha svolto attività di volontariato a {count} eventi organizzati da {association} {period}, con i seguenti ruoli:",
            shiftManyOneRole = "{firstName} ha svolto attività di volontariato a {count} eventi organizzati da {association} {period}, come {role}.",
            photoOne = "In data {date}, {firstName} ha curato la copertura fotografica e video di un evento organizzato da {association}.",
            photoMany = "{firstName} ha curato la copertura fotografica e video di {count} eventi organizzati da {association} {period}.",
            graphicEventOne = "In data {date}, {firstName} ha realizzato la grafica di un evento organizzato da {association}.",
            graphicEventMany = "{firstName} ha realizzato la grafica di {count} eventi organizzati da {association} {period}.",
            graphicAssociation = "{firstName} ha contribuito all'identità grafica dell'associazione {period}.",
            otherOne = "In data {date}, {firstName} ha svolto un incarico per {association} come {role}.",
            otherMany = "{firstName} ha svolto {count} incarichi per {association} {period}, con i seguenti ruoli:",
            otherManyOneRole = "{firstName} ha svolto {count} incarichi per {association} {period}, come {role}.",
            meetingOne = "In data {date}, {firstName} ha partecipato a una riunione organizzata da {association}.",
            meetingMany = "{firstName} ha partecipato a {count} riunioni organizzate da {association} {period}.",
            closing = "Ringraziamo calorosamente {firstName} per il suo impegno.",
            dateLine = "Fatto a {place}, il {today}",
            mailSubject = "La tua attestazione di volontariato",
            mailBody = "Ciao {firstName}, ecco la tua attestazione di volontariato per {association}. La trovi in allegato a questa e-mail.",
        ),
        "es" to Texts(
            title = "Certificado de voluntariado",
            introOne = "{association} certifica que {firstName} {lastName} ha realizado tareas de voluntariado en la asociación.",
            introMany = "{association} certifica que {firstName} {lastName} ha realizado tareas de voluntariado en la asociación {period}.",
            mandate = "{firstName} ejerció el cargo de {role} {period}.",
            shiftOne = "El {date}, {firstName} realizó tareas de voluntariado en un evento organizado por {association}, en calidad de {role}.",
            shiftMany = "{firstName} realizó tareas de voluntariado en {count} eventos organizados por {association} {period}, con las siguientes funciones:",
            shiftManyOneRole = "{firstName} realizó tareas de voluntariado en {count} eventos organizados por {association} {period}, en calidad de {role}.",
            photoOne = "El {date}, {firstName} se encargó de la cobertura fotográfica y de vídeo de un evento organizado por {association}.",
            photoMany = "{firstName} se encargó de la cobertura fotográfica y de vídeo de {count} eventos organizados por {association} {period}.",
            graphicEventOne = "El {date}, {firstName} diseñó los elementos gráficos de un evento organizado por {association}.",
            graphicEventMany = "{firstName} diseñó los elementos gráficos de {count} eventos organizados por {association} {period}.",
            graphicAssociation = "{firstName} contribuyó a la identidad gráfica de la asociación {period}.",
            otherOne = "El {date}, {firstName} llevó a cabo una misión para {association} como {role}.",
            otherMany = "{firstName} llevó a cabo {count} misiones para {association} {period}, con las siguientes funciones:",
            otherManyOneRole = "{firstName} llevó a cabo {count} misiones para {association} {period}, como {role}.",
            meetingOne = "El {date}, {firstName} participó en una reunión organizada por {association}.",
            meetingMany = "{firstName} participó en {count} reuniones organizadas por {association} {period}.",
            closing = "Agradecemos sinceramente a {firstName} su compromiso.",
            dateLine = "Expedido en {place}, a {today}",
            mailSubject = "Tu certificado de voluntariado",
            mailBody = "Hola {firstName}, aquí tienes tu certificado de voluntariado para {association}. Va adjunto a este correo.",
        ),
    )

    private val gender = Regex("""\{(\w+)}|\[([^\]|]*)\|([^\]|]*)\|([^\]|]*)]""")

    private fun runs(template: String, bold: Boolean = false): List<CertRun> {
        val out = mutableListOf<CertRun>()
        var last = 0
        for (m in gender.findAll(template)) {
            if (m.range.first > last) out += CertRun(text = template.substring(last, m.range.first), b = bold)
            val name = m.groups[1]?.value
            out += if (name != null) {
                CertRun(variable = name, b = bold)
            } else {
                CertRun(gender = listOf(m.groups[2]!!.value, m.groups[3]!!.value, m.groups[4]!!.value), b = bold)
            }
            last = m.range.last + 1
        }
        if (last < template.length) out += CertRun(text = template.substring(last), b = bold)
        return out
    }

    private fun para(
        template: String,
        align: CertAlign = CertAlign.LEFT,
        size: CertSize = CertSize.NORMAL,
        bold: Boolean = false,
        bullet: Boolean = false,
    ) = CertParagraph(align, size, bullet, runs(template, bold))

    /** A paragraph holding only one variable. */
    private fun only(variable: String): CertParagraph {
        val paragraph = para("{$variable}")
        // A description stands apart from the sentence above it.
        return if (variable == "description") paragraph.copy(runs = paragraph.runs.map { it.copy(i = true) }) else paragraph
    }

    private fun fixed(paragraph: CertParagraph) = CertBlock(one = listOf(paragraph))

    fun template(language: String, associationName: String): CertificateTemplate {
        val lang = if (language in LANGUAGES) language else "en"
        val t = texts.getValue(lang)
        val blocks = linkedMapOf(
            "header" to fixed(para("{association}", CertAlign.CENTER, CertSize.LARGE, bold = true)),
            "title" to fixed(para(t.title, CertAlign.CENTER, CertSize.TITLE, bold = true)),
            "intro" to CertBlock(
                one = listOf(para(t.introOne)),
                many = listOf(para(t.introMany)),
                manyOneRole = listOf(para(t.introMany)),
            ),
            "MANDATE" to CertBlock(one = listOf(para(t.mandate), only("description"))),
            "SHIFT" to CertBlock(
                one = listOf(para(t.shiftOne), only("description")),
                many = listOf(para(t.shiftMany), only("roles")),
                manyOneRole = listOf(para(t.shiftManyOneRole), only("description")),
                line = roleLine(),
            ),
            "PHOTO" to CertBlock(one = listOf(para(t.photoOne)), many = listOf(para(t.photoMany))),
            "GRAPHIC_EVENT" to CertBlock(
                one = listOf(para(t.graphicEventOne)),
                many = listOf(para(t.graphicEventMany)),
            ),
            "GRAPHIC_ASSOCIATION" to CertBlock(
                one = listOf(para(t.graphicAssociation)),
                many = listOf(para(t.graphicAssociation)),
            ),
            "OTHER" to CertBlock(
                one = listOf(para(t.otherOne), only("description")),
                many = listOf(para(t.otherMany), only("roles")),
                manyOneRole = listOf(para(t.otherManyOneRole), only("description")),
                line = roleLine(),
            ),
            "MEETING" to CertBlock(one = listOf(para(t.meetingOne)), many = listOf(para(t.meetingMany))),
            "closing" to fixed(para(t.closing)),
            "dateLine" to fixed(para(t.dateLine)),
            // Optional: one empty paragraph, dropped when rendering.
            "footer" to fixed(CertParagraph(CertAlign.CENTER, CertSize.SMALL, false, listOf(CertRun()))),
        )
        return CertificateTemplate(
            language = lang,
            mail = CertMail(
                fromName = associationName,
                subject = t.mailSubject,
                body = t.mailBody,
            ),
            blocks = blocks,
        )
    }

    private fun roleLine() = listOf(
        CertParagraph(
            bullet = true,
            runs = listOf(CertRun(variable = "role", b = true), CertRun(text = ": "), CertRun(variable = "description")),
        ),
    )
}
