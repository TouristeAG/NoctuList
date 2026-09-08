package com.eventmanager.app.data.utils

import com.eventmanager.app.data.models.AccountHolderKey
import com.eventmanager.app.data.models.AccountHolderType
import com.eventmanager.app.data.models.AccountTransfer
import com.eventmanager.app.data.models.affectsAccountLedger

object AccountBalanceService {
    fun computeBalance(
        holderType: AccountHolderType,
        holderId: String,
        transfers: List<AccountTransfer>
    ): Double {
        return transfers
            .asSequence()
            .filter { it.holderType == holderType && it.holderId == holderId }
            .filter { it.affectsAccountLedger() }
            .sumOf { it.amount }
    }

    fun computeAllBalances(transfers: List<AccountTransfer>): Map<AccountHolderKey, Double> {
        val sums = mutableMapOf<AccountHolderKey, Double>()
        for (transfer in transfers) {
            if (!transfer.affectsAccountLedger()) continue
            val key = AccountHolderKey(transfer.holderType, transfer.holderId)
            sums[key] = (sums[key] ?: 0.0) + transfer.amount
        }
        return sums
    }

    fun patchBalance(
        current: Map<AccountHolderKey, Double>,
        transfer: AccountTransfer
    ): Map<AccountHolderKey, Double> {
        if (!transfer.affectsAccountLedger()) return current
        val key = AccountHolderKey(transfer.holderType, transfer.holderId)
        val updated = (current[key] ?: 0.0) + transfer.amount
        return current + (key to updated)
    }
}
