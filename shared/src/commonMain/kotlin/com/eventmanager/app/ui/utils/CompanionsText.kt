package com.eventmanager.app.ui.utils

import androidx.compose.runtime.Composable
import com.eventmanager.app.data.models.Benefit
import com.eventmanager.app.data.utils.GuestListOccupancy
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.companions_many
import com.eventmanager.app.resources.companions_one
import com.eventmanager.app.resources.free_entry
import org.jetbrains.compose.resources.stringResource

/** "+1 companion" / "+3 companions": the people who come in with the volunteer. */
@Composable
fun companionsText(count: Int): String =
    if (count == 1) stringResource(Res.string.companions_one)
    else stringResource(Res.string.companions_many, count)

/**
 * The entry line of a benefit: "Free entry (+2 companions)", "Free entry" or "+2 companions".
 * Null when the benefit has neither.
 */
@Composable
fun entryPerkText(benefit: Benefit, includeFreeEntry: Boolean = true): String? {
    val companions = GuestListOccupancy.friendInvitesFromBenefit(benefit)
    val freeEntry = includeFreeEntry && benefit.freeEntry
    return when {
        freeEntry && companions > 0 -> "${stringResource(Res.string.free_entry)} (${companionsText(companions)})"
        freeEntry -> stringResource(Res.string.free_entry)
        companions > 0 -> "👥 ${companionsText(companions)}"
        else -> null
    }
}
