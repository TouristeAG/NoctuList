package com.eventmanager.app.data.remote

import com.eventmanager.app.data.models.AccountHolderKey
import com.eventmanager.app.data.models.AccountTransfer
import com.eventmanager.app.data.models.AccountTransferSyncState
import com.eventmanager.app.data.repository.EventManagerRepository
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.data.utils.AccountBalanceService
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

sealed class FirebaseLedgerResult {
    data class Confirmed(val transfer: AccountTransfer) : FirebaseLedgerResult()
    data class Pending(val transfer: AccountTransfer) : FirebaseLedgerResult()
    data class Rejected(val reason: String, val transfer: AccountTransfer) : FirebaseLedgerResult()
}

/**
 * Transactional ledger for Firebase mode: accounts/{holderKey} + transfers/{sourceReference}.
 */
class FirebaseLedgerService(
    private val repository: EventManagerRepository,
    private val settingsManager: SettingsManager,
    private val firestoreGateway: FirestoreGateway,
) {
    suspend fun commitTransfer(transfer: AccountTransfer, orgId: String? = null): FirebaseLedgerResult {
        val targetOrg = orgId?.trim()?.takeIf { it.isNotBlank() }
            ?: transfer.firebaseOrgId.trim().takeIf { it.isNotBlank() }
            ?: settingsManager.getFirebaseOrgId().trim().takeIf { !isFirebaseOrgAllSentinel(it) }
            ?: settingsManager.getFirebaseLastSingleOrgId().trim()
        val pending = stampLedgerTransfer(transfer, targetOrg, AccountTransferSyncState.PENDING)
        val existing = repository.getAccountTransferBySourceReference(transfer.sourceReference)
        val persisted = if (existing == null) {
            val rowId = repository.insertAccountTransfer(pending)
            pending.copy(id = rowId)
        } else {
            val withId = pending.copy(id = existing.id)
            repository.updateAccountTransfer(withId)
            withId
        }

        if (targetOrg.isBlank() || !firestoreGateway.isAvailable()) {
            return FirebaseLedgerResult.Pending(persisted)
        }

        val holderKey = AccountHolderKey(transfer.holderType, transfer.holderId).storageKey()
        val holderTransfers = repository.getTransfersForHolder(transfer.holderType, transfer.holderId)
            .filter { it.firebaseOrgId.isBlank() || it.firebaseOrgId == targetOrg }
        val balanceBefore = AccountBalanceService.computeBalance(
            transfer.holderType,
            transfer.holderId,
            holderTransfers.filter { it.sourceReference != transfer.sourceReference },
        )
        val newBalance = balanceBefore + transfer.amount
        val buffer = settingsManager.getPurchaseCreditBuffer()

        val commit = try {
            withContext(NonCancellable) {
                firestoreGateway.runLedgerTransaction(
                    orgId = targetOrg,
                    transfer = persisted,
                    holderKey = holderKey,
                    newBalance = newBalance,
                    buffer = buffer,
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            LedgerCommitResult.Unknown
        }

        return when (commit) {
            LedgerCommitResult.Accepted -> {
                val confirmed = stampLedgerTransfer(persisted, targetOrg, AccountTransferSyncState.CONFIRMED)
                repository.updateAccountTransfer(confirmed)
                FirebaseLedgerResult.Confirmed(confirmed)
            }
            LedgerCommitResult.RejectedInsufficientFunds -> {
                repository.deleteAccountTransfer(persisted)
                FirebaseLedgerResult.Rejected(
                    "Insufficient balance after peer updates (buffer=$buffer)",
                    persisted,
                )
            }
            LedgerCommitResult.Unknown -> {
                // Timeout / transport error: the server may already have the transfer.
                // Keep PENDING so a later retry uses the same sourceReference (idempotent).
                FirebaseLedgerResult.Pending(persisted)
            }
        }
    }
}
