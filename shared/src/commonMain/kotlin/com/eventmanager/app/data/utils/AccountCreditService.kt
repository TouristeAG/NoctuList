package com.eventmanager.app.data.utils

import com.eventmanager.app.data.models.AccountHolderType
import com.eventmanager.app.data.models.AccountTransfer
import com.eventmanager.app.data.models.AccountTransferSyncState
import com.eventmanager.app.data.models.AccountTransferType
import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.JobTypeConfig
import com.eventmanager.app.data.models.PosVenueScope
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.repository.EventManagerRepository
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.account_reversal_description
import com.eventmanager.app.resources.pos_deposit_return_blocked_limit
import com.eventmanager.app.resources.pos_deposit_return_blocked_none
import com.eventmanager.app.resources.pos_pay_cash_card
import com.eventmanager.app.resources.pos_sale_complete_message
import com.eventmanager.app.data.utils.NanoIdGenerator
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.getString

data class PosCartLine(
    val itemId: Long?,
    val name: String,
    val unitPrice: Double,
    val quantity: Int,
    val emoji: String = "",
    val barDiscountEligible: Boolean = false,
)

data class PosPaymentBreakdown(
    val creditPaid: Double,
    val cashOrCardDue: Double,
    val cashOrCardBeforeDiscount: Double,
) {
    val effectiveTotal: Double get() = creditPaid + cashOrCardDue
}

/**
 * Credits are charged at full price; bar discount applies only to the cash/card portion of
 * eligible lines.
 *
 * Deposit returns carry a negative price and settle purely against credit — a negative
 * [PosPaymentBreakdown.creditPaid] is money going back onto the account. They are applied before
 * the charges so a "buy a glass, give one back" basket nets to zero instead of asking for cash.
 */
fun computePosPayment(
    cart: List<PosCartLine>,
    accountBalance: Double,
    barDiscountPercent: Int,
    purchaseCreditBuffer: Double = 0.0,
): PosPaymentBreakdown {
    if (cart.isEmpty()) {
        return PosPaymentBreakdown(0.0, 0.0, 0.0)
    }

    val cartTotal = cart.sumOf { it.unitPrice * it.quantity }
    var creditRemaining = accountBalance.coerceAtLeast(0.0)
    var creditPaid = 0.0
    val unpaidSegments = mutableListOf<Pair<Double, Boolean>>()

    for (line in cart.sortedBy { if (it.unitPrice < 0.0) 0 else 1 }) {
        val lineTotal = line.unitPrice * line.quantity
        val fromCredit = minOf(creditRemaining, lineTotal)
        creditRemaining -= fromCredit
        creditPaid += fromCredit

        val unpaidAtFullPrice = lineTotal - fromCredit
        if (unpaidAtFullPrice > 0.0) {
            unpaidSegments += unpaidAtFullPrice to line.barDiscountEligible
        }
    }

    val cashBeforeDiscount = unpaidSegments.sumOf { it.first }
    val buffer = purchaseCreditBuffer.coerceAtLeast(0.0)

    // Absorb small shortfall into credit when balance is still positive (may go negative).
    if (accountBalance > 0.0 && cashBeforeDiscount > 0.0 && cashBeforeDiscount <= buffer) {
        return PosPaymentBreakdown(
            creditPaid = cartTotal,
            cashOrCardDue = 0.0,
            cashOrCardBeforeDiscount = 0.0,
        )
    }

    val discountRate = barDiscountPercent.coerceIn(0, 100) / 100.0
    val cashDue = unpaidSegments.sumOf { (unpaid, eligible) ->
        if (eligible && discountRate > 0.0) {
            unpaid * (1.0 - discountRate)
        } else {
            unpaid
        }
    }

    return PosPaymentBreakdown(
        creditPaid = creditPaid,
        cashOrCardDue = cashDue,
        cashOrCardBeforeDiscount = cashBeforeDiscount,
    )
}

/**
 * Net ledger movement for a POS sale on the holder's credit account.
 *
 * - [PosPaymentBreakdown.creditPaid] is always debited at full price.
 * - When the holder had **no credits to apply** ([creditPaid] == 0) and pays cash/card for
 *   the shortfall, debt is the credit-priced remainder minus the discounted cash actually paid
 *   (`cashOrCardBeforeDiscount - cashOrCardDue`). That way a future shift credit is not reduced
 *   again for money already paid in cash.
 * - When credits already cover part of the basket, cash settles the remainder (incl. bar
 *   discount) and does not create extra debt beyond the credits debited.
 */
fun computePosLedgerAmount(payment: PosPaymentBreakdown): Double {
    val cashDebt = if (payment.creditPaid == 0.0 && payment.cashOrCardDue > 0.0) {
        (payment.cashOrCardBeforeDiscount - payment.cashOrCardDue).coerceAtLeast(0.0)
    } else {
        0.0
    }
    return -(payment.creditPaid + cashDebt)
}

data class PosSaleResult(
    val success: Boolean,
    val totalAmount: Double,
    val creditPaid: Double,
    val cashOrCardDue: Double,
    val cashOrCardBeforeDiscount: Double = cashOrCardDue,
    val barDiscountPercent: Int = 0,
    val remainingBalance: Double,
    val message: String,
    val wentNegativeViaBuffer: Boolean = false,
    val transfer: AccountTransfer? = null,
)

/**
 * A PENDING Firebase POS row for the same cart must be retried in place. Creating a new
 * [AccountTransfer.sourceReference] after a timeout/cancellation is what produced duplicate
 * sales on Android.
 */
fun findReusablePendingPosSale(
    existing: List<AccountTransfer>,
    holderType: AccountHolderType,
    holderId: String,
    posItemsJson: String,
    posVenueName: String,
    firebaseOrgId: String,
): AccountTransfer? = existing
    .asSequence()
    .filter { it.type == AccountTransferType.POS_SALE }
    .filter { it.syncState == AccountTransferSyncState.PENDING }
    .filter { it.holderType == holderType && it.holderId == holderId }
    .filter { it.posItemsJson == posItemsJson }
    .filter { it.posVenueName == posVenueName }
    .filter { it.firebaseOrgId.isBlank() || firebaseOrgId.isBlank() || it.firebaseOrgId == firebaseOrgId }
    .maxByOrNull { it.createdAt }

/** Wording shared by the POS pre-check and the authoritative check in [AccountCreditService]. */
suspend fun depositRefusalMessage(refusal: DepositReturnPolicy.Refusal): String =
    if (refusal.allowed <= 0) {
        getString(Res.string.pos_deposit_return_blocked_none, refusal.returnName)
    } else {
        getString(Res.string.pos_deposit_return_blocked_limit, refusal.returnName, refusal.allowed)
    }

class AccountCreditService(
    private val repository: EventManagerRepository,
    private val currencyProvider: () -> String
) {
    suspend fun applyShiftCredits(
        job: Job,
        volunteer: Volunteer,
        jobTypeConfigs: List<JobTypeConfig>,
        offsetHours: Int
    ): List<AccountTransfer> {
        if (!ShiftCreditCalculator.isJobDayReached(job, offsetHours)) return emptyList()
        val volunteerJobs = repository.getJobsByVolunteer(job.volunteerId).first()
        val entries = ShiftCreditCalculator.creditsForAddedJob(job, volunteerJobs, jobTypeConfigs, offsetHours)
        return insertCreditEntries(entries, volunteer, job.firebaseOrgId)
    }

    suspend fun evaluatePendingShiftCredits(
        jobTypeConfigs: List<JobTypeConfig>,
        offsetHours: Int,
        volunteerId: String? = null
    ): List<AccountTransfer> {
        val allJobs = if (volunteerId != null) {
            repository.getJobsByVolunteer(volunteerId).first()
        } else {
            repository.getAllJobs().first()
        }
        val volunteersById = repository.getAllVolunteers().first().associateBy { it.id }
        val jobsByVolunteer = if (volunteerId != null) {
            mapOf(volunteerId to allJobs)
        } else {
            allJobs.groupBy { it.volunteerId }
        }
        val created = mutableListOf<AccountTransfer>()
        val now = System.currentTimeMillis()

        for ((volId, volJobs) in jobsByVolunteer) {
            val volunteer = volunteersById[volId] ?: continue
            for (job in volJobs) {
                val entries = ShiftCreditCalculator.creditsForJob(
                    job, volJobs, jobTypeConfigs, offsetHours, now
                )
                created += insertCreditEntries(entries, volunteer, job.firebaseOrgId)
            }
        }
        return created
    }

    private suspend fun insertCreditEntries(
        entries: List<ShiftCreditEntry>,
        volunteer: Volunteer,
        fallbackOrgId: String = "",
    ): List<AccountTransfer> {
        val created = mutableListOf<AccountTransfer>()
        val orgId = volunteer.firebaseOrgId.trim().ifBlank { fallbackOrgId.trim() }
        for (entry in entries) {
            if (repository.getAccountTransferBySourceReference(entry.sourceReference) != null) continue
            val transfer = AccountTransfer(
                holderType = AccountHolderType.VOLUNTEER,
                holderId = volunteer.id,
                holderName = volunteer.name,
                amount = entry.amount,
                currencyCode = currencyProvider(),
                type = AccountTransferType.SHIFT_CREDIT,
                sourceReference = entry.sourceReference,
                jobReferenceKey = entry.jobReferenceKey,
                jobTypeName = entry.jobTypeName,
                jobDate = entry.jobDate,
                description = entry.description,
                posVenueName = PosVenueScope.venueFromJobReferenceKey(entry.jobReferenceKey),
                firebaseOrgId = orgId,
            )
            repository.insertAccountTransfer(transfer)
            created += transfer
        }
        return created
    }

    suspend fun reverseShiftCredits(
        job: Job,
        volunteer: Volunteer,
        jobTypeConfigs: List<JobTypeConfig>,
        offsetHours: Int
    ): List<AccountTransfer> {
        val volunteerJobs = repository.getJobsByVolunteer(job.volunteerId).first()
        val sourceRefs = ShiftCreditCalculator.sourceReferencesForRemovedJob(job, volunteerJobs, jobTypeConfigs, offsetHours)
        val created = mutableListOf<AccountTransfer>()
        val currentBalance = AccountBalanceService.computeBalance(
            AccountHolderType.VOLUNTEER,
            volunteer.id,
            repository.getTransfersForHolder(AccountHolderType.VOLUNTEER, volunteer.id),
        )
        var remainingBalance = currentBalance

        for (sourceRef in sourceRefs) {
            val original = repository.getAccountTransferBySourceReference(sourceRef) ?: continue
            val reversalRef = "reversal:$sourceRef"
            if (repository.getAccountTransferBySourceReference(reversalRef) != null) continue
            val reversalAmount = -minOf(original.amount, maxOf(0.0, remainingBalance))
            if (reversalAmount == 0.0) continue
            remainingBalance += reversalAmount
            val transfer = AccountTransfer(
                holderType = AccountHolderType.VOLUNTEER,
                holderId = volunteer.id,
                holderName = volunteer.name,
                amount = reversalAmount,
                currencyCode = currencyProvider(),
                type = AccountTransferType.SHIFT_REVERSAL,
                sourceReference = reversalRef,
                jobReferenceKey = original.jobReferenceKey,
                jobTypeName = original.jobTypeName,
                jobDate = original.jobDate,
                description = getString(Res.string.account_reversal_description, original.description),
                posVenueName = original.posVenueName.ifBlank {
                    PosVenueScope.venueFromJobReferenceKey(original.jobReferenceKey)
                },
                firebaseOrgId = original.firebaseOrgId.ifBlank { volunteer.firebaseOrgId },
            )
            repository.insertAccountTransfer(transfer)
            created += transfer
        }
        return created
    }

    suspend fun applyManualAdjustment(
        holderType: AccountHolderType,
        holderId: String,
        holderName: String,
        amount: Double,
        note: String,
        firebaseOrgId: String = "",
    ): AccountTransfer {
        val transfer = AccountTransfer(
            holderType = holderType,
            holderId = holderId,
            holderName = holderName,
            amount = amount,
            currencyCode = currencyProvider(),
            type = AccountTransferType.MANUAL_ADJUSTMENT,
            sourceReference = "manual:${NanoIdGenerator.generateGuestId()}:${holderId}",
            description = note,
            firebaseOrgId = firebaseOrgId,
        )
        repository.insertAccountTransfer(transfer)
        return transfer
    }

    suspend fun completePosSale(
        holderType: AccountHolderType,
        holderId: String,
        holderName: String,
        cart: List<PosCartLine>,
        barDiscountPercent: Int = 0,
        posVenueName: String = PosVenueScope.GLOBAL,
        purchaseCreditBuffer: Double = 0.0,
        firebaseOrgId: String = "",
        writeAsPending: Boolean = false,
    ): PosSaleResult {
        val holderTransfers = repository.getTransfersForHolder(holderType, holderId)
        val balance = AccountBalanceService.computeBalance(
            holderType,
            holderId,
            holderTransfers,
        )

        // Authoritative deposit check: the POS blocks these at add-to-cart time, but the cart it
        // sends is a snapshot and another till may have consumed the same purchase meanwhile.
        DepositReturnPolicy.firstRefusal(cart, holderTransfers, System.currentTimeMillis())?.let { refusal ->
            return PosSaleResult(
                success = false,
                totalAmount = 0.0,
                creditPaid = 0.0,
                cashOrCardDue = 0.0,
                remainingBalance = balance,
                message = depositRefusalMessage(refusal),
            )
        }

        val payment = computePosPayment(cart, balance, barDiscountPercent, purchaseCreditBuffer)
        val creditPaid = payment.creditPaid
        val cashDue = payment.cashOrCardDue
        val barDiscountApplied = barDiscountPercent.takeIf {
            it > 0 && cashDue > 0 && payment.cashOrCardBeforeDiscount > cashDue
        }
        val ledgerAmount = computePosLedgerAmount(payment)

        // A pure deposit return has a negative creditPaid and no cash — it still needs a ledger row.
        if (creditPaid != 0.0 || cashDue > 0) {
            val itemsSummary = cart.joinToString("; ") { "${it.quantity}x ${it.name}" }
            val posJson = cart.joinToString("|") { "${it.itemId ?: 0}:${it.name}:${it.unitPrice}:${it.quantity}" }
            findReusablePendingPosSale(
                existing = holderTransfers,
                holderType = holderType,
                holderId = holderId,
                posItemsJson = posJson,
                posVenueName = posVenueName,
                firebaseOrgId = firebaseOrgId,
            )?.let { pending ->
                val remainingBalance = AccountBalanceService.computeBalance(
                    holderType,
                    holderId,
                    holderTransfers,
                )
                val reusedCredit = pending.creditAmountPaid ?: creditPaid
                val reusedCash = pending.cashAmountPaid ?: cashDue
                val wentNegativeViaBuffer = balance > 0.0 && remainingBalance < 0.0 && reusedCredit > 0.0
                return posSaleSuccessResult(
                    payment = payment,
                    barDiscountPercent = barDiscountPercent,
                    remainingBalance = remainingBalance,
                    wentNegativeViaBuffer = wentNegativeViaBuffer,
                    transfer = pending,
                    creditPaid = reusedCredit,
                    cashDue = reusedCash,
                )
            }
            val transfer = AccountTransfer(
                holderType = holderType,
                holderId = holderId,
                holderName = holderName,
                amount = ledgerAmount,
                currencyCode = currencyProvider(),
                type = AccountTransferType.POS_SALE,
                sourceReference = "pos:${NanoIdGenerator.generateGuestId()}:$holderId",
                description = itemsSummary,
                creditAmountPaid = creditPaid.takeIf { it > 0 },
                cashAmountPaid = cashDue.takeIf { it > 0 },
                posBarDiscountPercent = barDiscountApplied,
                posItemsJson = posJson,
                posVenueName = posVenueName,
                firebaseOrgId = firebaseOrgId,
                syncState = if (writeAsPending) {
                    AccountTransferSyncState.PENDING
                } else {
                    AccountTransferSyncState.CONFIRMED
                },
            )
            repository.insertAccountTransfer(transfer)
            val remainingBalance = AccountBalanceService.computeBalance(
                holderType,
                holderId,
                holderTransfers + transfer,
            )
            val wentNegativeViaBuffer = balance > 0.0 && remainingBalance < 0.0 && creditPaid > 0.0

            return posSaleSuccessResult(
                payment = payment,
                barDiscountPercent = barDiscountPercent,
                remainingBalance = remainingBalance,
                wentNegativeViaBuffer = wentNegativeViaBuffer,
                transfer = transfer,
                creditPaid = creditPaid,
                cashDue = cashDue,
            )
        }

        val remainingBalance = balance
        val wentNegativeViaBuffer = balance > 0.0 && remainingBalance < 0.0 && creditPaid > 0.0

        return posSaleSuccessResult(
            payment = payment,
            barDiscountPercent = barDiscountPercent,
            remainingBalance = remainingBalance,
            wentNegativeViaBuffer = wentNegativeViaBuffer,
            transfer = null,
            creditPaid = creditPaid,
            cashDue = cashDue,
        )
    }

    private suspend fun posSaleSuccessResult(
        payment: PosPaymentBreakdown,
        barDiscountPercent: Int,
        remainingBalance: Double,
        wentNegativeViaBuffer: Boolean,
        transfer: AccountTransfer?,
        creditPaid: Double,
        cashDue: Double,
    ): PosSaleResult {
        val currency = currencyProvider()
        return PosSaleResult(
            success = true,
            totalAmount = payment.effectiveTotal,
            creditPaid = creditPaid,
            cashOrCardDue = cashDue,
            cashOrCardBeforeDiscount = payment.cashOrCardBeforeDiscount,
            barDiscountPercent = barDiscountPercent,
            remainingBalance = remainingBalance,
            message = if (cashDue > 0) {
                getString(Res.string.pos_pay_cash_card, formatMoney(cashDue, currency))
            } else {
                getString(Res.string.pos_sale_complete_message)
            },
            wentNegativeViaBuffer = wentNegativeViaBuffer,
            transfer = transfer,
        )
    }
}
