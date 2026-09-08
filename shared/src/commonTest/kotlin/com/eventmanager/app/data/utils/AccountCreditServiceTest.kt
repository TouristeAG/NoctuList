package com.eventmanager.app.data.utils

import com.eventmanager.app.data.models.AccountHolderType
import com.eventmanager.app.data.models.AccountTransfer
import com.eventmanager.app.data.models.AccountTransferSyncState
import com.eventmanager.app.data.models.AccountTransferType
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountCreditServiceTest {

    private fun line(
        price: Double,
        qty: Int = 1,
        eligible: Boolean = true,
    ) = PosCartLine(
        itemId = 1L,
        name = "Beer",
        unitPrice = price,
        quantity = qty,
        barDiscountEligible = eligible,
    )

    @Test
    fun computePosPayment_appliesCreditBeforeBarDiscount() {
        val cart = listOf(line(14.0))
        val payment = computePosPayment(cart, accountBalance = 6.0, barDiscountPercent = 50)

        assertEquals(6.0, payment.creditPaid, 1e-9)
        assertEquals(8.0, payment.cashOrCardBeforeDiscount, 1e-9)
        assertEquals(4.0, payment.cashOrCardDue, 1e-9)
        assertEquals(10.0, payment.effectiveTotal, 1e-9)
    }

    @Test
    fun computePosPayment_noCredit_discountsFullEligibleCart() {
        val cart = listOf(line(14.0))
        val payment = computePosPayment(cart, accountBalance = 0.0, barDiscountPercent = 50)

        assertEquals(0.0, payment.creditPaid, 1e-9)
        assertEquals(14.0, payment.cashOrCardBeforeDiscount, 1e-9)
        assertEquals(7.0, payment.cashOrCardDue, 1e-9)
    }

    @Test
    fun computePosPayment_multiLineFifoCreditThenDiscount() {
        val cart = listOf(
            line(10.0, eligible = true),
            line(4.0, eligible = true),
        )
        val payment = computePosPayment(cart, accountBalance = 6.0, barDiscountPercent = 50)

        assertEquals(6.0, payment.creditPaid, 1e-9)
        assertEquals(8.0, payment.cashOrCardBeforeDiscount, 1e-9)
        assertEquals(4.0, payment.cashOrCardDue, 1e-9)
    }

    @Test
    fun computePosLedgerAmount_partialCredit_noExtraCashDebt() {
        val payment = computePosPayment(listOf(line(14.0)), accountBalance = 6.0, barDiscountPercent = 50)
        assertEquals(-6.0, computePosLedgerAmount(payment), 1e-9)
    }

    @Test
    fun computePosLedgerAmount_fullCashWithDiscount_recordsCashDebt() {
        val payment = computePosPayment(listOf(line(14.0)), accountBalance = 0.0, barDiscountPercent = 50)
        assertEquals(-7.0, computePosLedgerAmount(payment), 1e-9)
    }

    private fun depositReturn(price: Double, qty: Int = 1) = PosCartLine(
        itemId = -2L,
        name = "Retour Verre",
        unitPrice = -price,
        quantity = qty,
        barDiscountEligible = false,
    )

    @Test
    fun computePosPayment_loneDepositReturn_creditsTheAccount() {
        val payment = computePosPayment(listOf(depositReturn(2.0)), accountBalance = 0.0, barDiscountPercent = 0)

        assertEquals(-2.0, payment.creditPaid, 1e-9)
        assertEquals(0.0, payment.cashOrCardDue, 1e-9)
        assertEquals(2.0, computePosLedgerAmount(payment), 1e-9)
    }

    @Test
    fun computePosPayment_depositReturn_repaysAnOverdraft() {
        val payment = computePosPayment(listOf(depositReturn(2.0)), accountBalance = -5.0, barDiscountPercent = 0)

        assertEquals(2.0, computePosLedgerAmount(payment), 1e-9)
    }

    @Test
    fun computePosPayment_returnFundsThePurchaseEvenWhenItIsListedLast() {
        val cart = listOf(
            PosCartLine(itemId = 2L, name = "Verre", unitPrice = 2.0, quantity = 1),
            depositReturn(2.0),
        )
        val payment = computePosPayment(cart, accountBalance = 0.0, barDiscountPercent = 0)

        assertEquals(0.0, payment.creditPaid, 1e-9)
        assertEquals(0.0, payment.cashOrCardDue, 1e-9)
        assertEquals(0.0, computePosLedgerAmount(payment), 1e-9)
    }

    @Test
    fun computePosPayment_returnCoversPartOfAPurchaseBeforeCash() {
        val cart = listOf(
            PosCartLine(itemId = 3L, name = "Bière", unitPrice = 5.0, quantity = 1),
            depositReturn(2.0),
        )
        val payment = computePosPayment(cart, accountBalance = 0.0, barDiscountPercent = 0)

        assertEquals(3.0, payment.cashOrCardDue, 1e-9)
    }

    @Test
    fun findReusablePendingPosSale_returnsSameCartPendingRow() {
        val pending = AccountTransfer(
            holderType = AccountHolderType.VOLUNTEER,
            holderId = "v1",
            holderName = "Ada",
            amount = -10.0,
            type = AccountTransferType.POS_SALE,
            sourceReference = "pos:first:v1",
            posItemsJson = "1:Beer:5.0:2",
            posVenueName = "Bar",
            syncState = AccountTransferSyncState.PENDING,
            firebaseOrgId = "org-a",
        )
        val confirmedTwin = pending.copy(
            sourceReference = "pos:second:v1",
            syncState = AccountTransferSyncState.CONFIRMED,
        )
        val otherCart = pending.copy(
            sourceReference = "pos:other:v1",
            posItemsJson = "1:Beer:5.0:1",
        )
        val found = findReusablePendingPosSale(
            existing = listOf(confirmedTwin, otherCart, pending),
            holderType = AccountHolderType.VOLUNTEER,
            holderId = "v1",
            posItemsJson = "1:Beer:5.0:2",
            posVenueName = "Bar",
            firebaseOrgId = "org-a",
        )
        assertEquals("pos:first:v1", found?.sourceReference)
    }

    @Test
    fun findReusablePendingPosSale_ignoresConfirmedDuplicates() {
        val confirmed = AccountTransfer(
            holderType = AccountHolderType.GUEST,
            holderId = "g1",
            holderName = "Bo",
            amount = -5.0,
            type = AccountTransferType.POS_SALE,
            sourceReference = "pos:done:g1",
            posItemsJson = "2:Ticket:5.0:1",
            syncState = AccountTransferSyncState.CONFIRMED,
        )
        val found = findReusablePendingPosSale(
            existing = listOf(confirmed),
            holderType = AccountHolderType.GUEST,
            holderId = "g1",
            posItemsJson = "2:Ticket:5.0:1",
            posVenueName = "",
            firebaseOrgId = "",
        )
        assertEquals(null, found)
    }
}
