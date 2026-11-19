package com.eventmanager.app.resources

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class StringsEscapeTest {
    @Test
    fun noEscapedApostropheInComposeStrings() {
        val dir = File("src/commonMain/composeResources")
        val offenders = dir.walk().filter { it.name == "strings.xml" }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if ("\\'" in l) "${f.parentFile.name}/strings.xml:${i + 1}" else null } }
            .toList()
        assertEquals(emptyList(), offenders)
    }
}
