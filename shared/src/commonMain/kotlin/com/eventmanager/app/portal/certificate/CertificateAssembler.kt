package com.eventmanager.app.portal.certificate

data class CertJobType(val name: String, val category: CertificateCategory, val description: String)

/** [gender] is the name of the Gender enum, or "" when unknown. */
data class CertPerson(val firstName: String, val lastName: String, val gender: String)

data class CertJob(val jobTypeName: String, val day: Int)

data class CertSelection(val fromDay: Int, val toDay: Int, val todayDay: Int, val jobTypes: Set<String>)

data class ResolvedRun(val text: String, val b: Boolean, val i: Boolean, val u: Boolean)

data class ResolvedParagraph(
    val align: CertAlign,
    val size: CertSize,
    val bullet: Boolean,
    val runs: List<ResolvedRun>,
)

/** [footer] is shown at the bottom of every page, [body] is the rest of the document. */
data class CertAssembly(val body: List<ResolvedParagraph>, val footer: List<ResolvedParagraph>)

/**
 * Turns the admin template and the jobs of one volunteer into the paragraphs of the certificate.
 * The TypeScript copy in webform/functions must stay in step with webform/functions/test/certificate-cases.json.
 */
object CertificateAssembler {

    fun genderIndex(gender: String): Int = when (gender) {
        "MALE" -> 0
        "FEMALE" -> 1
        else -> 2
    }

    /** Fixes names typed in capitals only: "DUPONT" becomes "Dupont". Names with any lowercase letter are kept. */
    fun displayName(raw: String): String {
        val name = raw.trim()
        if (name.any { it.isLowerCase() }) return name
        val out = StringBuilder()
        var startOfWord = true
        for (c in name) {
            out.append(if (startOfWord) c.uppercaseChar() else c.lowercaseChar())
            startOfWord = c == ' ' || c == '-' || c == '\''
        }
        return out.toString()
    }

    /** Returns null when there is nothing to certify. */
    fun assemble(
        template: CertificateTemplate,
        association: String,
        person: CertPerson,
        types: List<CertJobType>,
        jobs: List<CertJob>,
        selection: CertSelection,
    ): CertAssembly? {
        val kept = jobs
            .filter { it.day in selection.fromDay..selection.toDay && it.day <= selection.todayDay && it.jobTypeName in selection.jobTypes }
            .sortedBy { it.day }
        if (kept.isEmpty()) return null

        val lang = template.language
        val byName = HashMap<String, CertJobType>()
        for (t in types) byName.putIfAbsent(t.name, t)
        fun typeOf(job: CertJob) = byName[job.jobTypeName] ?: CertJobType(job.jobTypeName, CertificateCategory.OTHER, "")

        val genderIndex = genderIndex(person.gender)
        val first = kept.first().day
        val last = kept.last().day
        val global = mapOf(
            "firstName" to displayName(person.firstName),
            "lastName" to displayName(person.lastName),
            "association" to association,
            "place" to template.place.trim(),
            "today" to CertificateDates.longDate(selection.todayDay, lang),
            "period" to CertificateDates.period(first, last, lang),
            "start" to CertificateDates.monthYear(first, lang),
            "end" to CertificateDates.monthYear(last, lang),
            "date" to if (kept.size == 1) CertificateDates.longDate(first, lang) else "",
        )

        fun resolve(paragraph: CertParagraph, vars: Map<String, String>): ResolvedParagraph? {
            val runs = paragraph.runs.map { run ->
                val text = when {
                    run.variable.isNotEmpty() -> vars[run.variable] ?: ""
                    run.gender.isNotEmpty() -> run.gender.getOrElse(genderIndex) { "" }
                    else -> run.text
                }
                ResolvedRun(text, run.b, run.i, run.u)
            }.filter { it.text.isNotEmpty() }
            if (runs.all { it.text.isBlank() }) return null
            val merged = mutableListOf<ResolvedRun>()
            for (run in runs) {
                val prev = merged.lastOrNull()
                if (prev != null && prev.b == run.b && prev.i == run.i && prev.u == run.u) {
                    merged[merged.size - 1] = prev.copy(text = prev.text + run.text)
                } else {
                    merged += run
                }
            }
            return ResolvedParagraph(paragraph.align, paragraph.size, paragraph.bullet, merged)
        }

        fun resolveAll(paragraphs: List<CertParagraph>, vars: Map<String, String>) =
            paragraphs.mapNotNull { resolve(it, vars) }

        fun block(key: String, variant: (CertBlock) -> List<CertParagraph>, vars: Map<String, String>) =
            template.blocks[key]?.let { resolveAll(variant(it), vars) } ?: emptyList()

        /** A paragraph made only of the {roles} variable. */
        fun isRolesParagraph(p: CertParagraph) =
            p.runs.any { it.variable == "roles" } &&
                p.runs.all { it.variable == "roles" || (it.variable.isEmpty() && it.gender.isEmpty() && it.text.isBlank()) }

        /** Drops the description and the separator written just before it. */
        fun withoutDescription(p: CertParagraph): CertParagraph {
            val runs = p.runs.toMutableList()
            var i = runs.lastIndex
            while (i >= 0) {
                if (runs[i].variable == "description") {
                    runs.removeAt(i)
                    val before = runs.getOrNull(i - 1)
                    if (before != null && before.variable.isEmpty() && before.gender.isEmpty()) {
                        runs[i - 1] = before.copy(text = before.text.trimEnd { it.isWhitespace() || it in ":;,.-–—" })
                    }
                }
                i--
            }
            return p.copy(runs = runs)
        }

        val body = mutableListOf<ResolvedParagraph>()
        body += block("header", { it.one }, global)
        body += block("title", { it.one }, global)
        body += block("intro", { if (kept.size == 1) it.one else it.many }, global)

        for (category in CertificateCategory.entries) {
            val ofCategory = kept.filter { typeOf(it).category == category }
            if (ofCategory.isEmpty()) continue
            val key = category.name

            fun local(group: List<CertJob>, withType: CertJobType?): Map<String, String> {
                val vars = global.toMutableMap()
                vars["count"] = group.size.toString()
                vars["period"] = CertificateDates.period(group.first().day, group.last().day, lang)
                if (group.size == 1) vars["date"] = CertificateDates.longDate(group.first().day, lang)
                if (withType != null) {
                    vars["role"] = withType.name
                    vars["description"] = withType.description
                }
                return vars
            }

            val byType = ofCategory.groupBy { it.jobTypeName }
            when (category) {
                CertificateCategory.MANDATE -> for ((_, group) in byType) {
                    body += block(key, { it.one }, local(group, typeOf(group.first())))
                }
                CertificateCategory.GRAPHIC_ASSOCIATION -> body += block(key, { it.one }, local(ofCategory, null))
                CertificateCategory.SHIFT, CertificateCategory.OTHER -> when {
                    ofCategory.size == 1 ->
                        body += block(key, { it.one }, local(ofCategory, typeOf(ofCategory.first())))
                    byType.size == 1 ->
                        body += block(key, { it.manyOneRole }, local(ofCategory, typeOf(ofCategory.first())))
                    else -> {
                        val vars = local(ofCategory, null)
                        val lines = byType.values
                            .sortedWith(compareBy({ -it.size }, { it.first().jobTypeName }))
                            .flatMap { group ->
                                val type = typeOf(group.first())
                                val lineVars = local(group, type)
                                (template.blocks[key]?.line ?: emptyList()).mapNotNull { p ->
                                    resolve(if (type.description.isBlank()) withoutDescription(p) else p, lineVars)
                                }
                            }
                        for (p in template.blocks[key]?.many ?: emptyList()) {
                            if (isRolesParagraph(p)) body += lines else resolve(p, vars)?.let { body += it }
                        }
                    }
                }
                else -> body += block(key, { if (ofCategory.size == 1) it.one else it.many }, local(ofCategory, null))
            }
        }

        body += block("closing", { it.one }, global)
        if (template.place.isNotBlank()) body += block("dateLine", { it.one }, global)

        return CertAssembly(body, block("footer", { it.one }, global))
    }
}
