package com.eventmanager.app.data.models

import com.eventmanager.app.ui.components.sanitizeBarDiscountInput
import kotlin.test.Test
import kotlin.test.assertEquals

class GuestBarDiscountTest {

    private fun guest(
        percent: Int,
        volunteerBenefit: Boolean = false,
        temporary: Boolean = false,
    ) = Guest(
        name = "Ada",
        invitations = 1,
        venueName = "GROOVE",
        isVolunteerBenefit = volunteerBenefit,
        isTemporaryGuest = temporary,
        barDiscountPercent = percent,
    )

    @Test
    fun activeBarDiscount_isZeroOnSheetsBackend() {
        assertEquals(0, guest(30).activeBarDiscountPercent(firebaseBackend = false))
    }

    @Test
    fun activeBarDiscount_appliesToPermanentGuestsOnFirebase() {
        assertEquals(30, guest(30).activeBarDiscountPercent(firebaseBackend = true))
        assertEquals(0, guest(0).activeBarDiscountPercent(firebaseBackend = true))
    }

    @Test
    fun activeBarDiscount_skipsVolunteerBenefitRows() {
        assertEquals(0, guest(30, volunteerBenefit = true).activeBarDiscountPercent(firebaseBackend = true))
    }

    @Test
    fun activeBarDiscount_appliesToTemporaryGuestsOnFirebaseOnly() {
        assertEquals(30, guest(30, temporary = true).activeBarDiscountPercent(firebaseBackend = true))
        assertEquals(0, guest(30, temporary = true).activeBarDiscountPercent(firebaseBackend = false))
    }

    @Test
    fun activeBarDiscount_clampsOutOfRangeStoredValues() {
        assertEquals(100, guest(180).activeBarDiscountPercent(firebaseBackend = true))
        assertEquals(0, guest(-20).activeBarDiscountPercent(firebaseBackend = true))
    }

    @Test
    fun sanitizeBarDiscountInput_keepsDigitsAndCaps() {
        assertEquals("", sanitizeBarDiscountInput(""))
        assertEquals("0", sanitizeBarDiscountInput("0"))
        assertEquals("5", sanitizeBarDiscountInput("05"))
        assertEquals("50", sanitizeBarDiscountInput("5o0"))
        assertEquals("100", sanitizeBarDiscountInput("150"))
    }
}
