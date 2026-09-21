package com.eventmanager.app.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class DialogFractionSizerTest {
    @Test
    fun capsHugeDialogConstraintsToTheScreen() {
        assertEquals(390.dp, finiteDialogConstraint(780_000.dp, 390.dp))
        assertEquals(390.dp, finiteDialogConstraint(Dp.Infinity, 390.dp))
        assertEquals(390.dp, finiteDialogConstraint(0.dp, 390.dp))
    }

    @Test
    fun keepsConstraintsThatAlreadyFitTheScreen() {
        assertEquals(360.dp, finiteDialogConstraint(360.dp, 390.dp))
        assertEquals(390.dp, finiteDialogConstraint(390.dp, 390.dp))
    }
}
