package com.eventmanager.app.data.reports

import com.eventmanager.app.data.models.AccountTransfer
import com.eventmanager.app.data.models.AccountTransferType
import com.eventmanager.app.data.models.affectsAccountLedger
import com.eventmanager.app.data.models.PosVenueScope
import com.eventmanager.app.data.models.SalesCategory
import com.eventmanager.app.data.models.SalesSheetItem
import com.eventmanager.app.data.utils.DateTimeUtils
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

object PosAccountingReportBuilder {

    private val GENEVA = TimeZone.getTimeZone("Europe/Zurich")

    fun defaultClosureFromOffset(offsetHours: Int): Pair<Int, Int> {
        val hour = ((offsetHours % 24) + 24) % 24
        return hour to 0
    }

    fun computeEveningRange(
        selectedDateMs: Long,
        settingsOffsetHours: Int,
        closureHour: Int,
        closureMinute: Int,
    ): Pair<Long, Long> {
        val start = DateTimeUtils.getStartOfDayWithOffset(selectedDateMs, settingsOffsetHours)
        val endCal = Calendar.getInstance(GENEVA).apply {
            timeInMillis = selectedDateMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, closureHour)
            set(Calendar.MINUTE, closureMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val end = endCal.timeInMillis - 1
        return start to end
    }

    fun computePeriodRange(
        startDateMs: Long,
        endDateMs: Long,
        settingsOffsetHours: Int,
    ): Pair<Long, Long> {
        val start = DateTimeUtils.getStartOfDayWithOffset(startDateMs, settingsOffsetHours)
        val end = DateTimeUtils.getEndOfDayWithOffset(endDateMs, settingsOffsetHours).timeInMillis
        return start to end
    }

    fun formatPeriodLabel(startMs: Long, endMs: Long): String {
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).apply { timeZone = GENEVA }
        return "${fmt.format(startMs)} – ${fmt.format(endMs)}"
    }

    fun formatClosureLabel(hour: Int, minute: Int): String =
        String.format(Locale.getDefault(), "%02d:%02d", hour, minute)

    fun formatDateOnly(ms: Long): String {
        val fmt = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).apply { timeZone = GENEVA }
        return fmt.format(ms)
    }

    fun build(
        transfers: List<AccountTransfer>,
        salesItems: List<SalesSheetItem>,
        period: PosReportPeriod,
        currencyCode: String,
        generatedAtMs: Long = System.currentTimeMillis(),
    ): PosAccountingReport {
        val inRange = transfers
            .filter { it.createdAt in period.startMs..period.endMs }
            .filter { it.affectsAccountLedger() }
            .filter { PosVenueScope.matchesTransferVenue(it.posVenueName, period.venueScope, period.venueName) }
            .sortedBy { it.createdAt }

        val (itemCategoryById, itemCategoryByName) = PosItemParser.categoryLookups(salesItems)

        val posSales = inRange
            .filter { it.type == AccountTransferType.POS_SALE }
            .map { transfer ->
                val lines = PosItemParser.parsePosItemsJson(
                    transfer.posItemsJson,
                    itemCategoryById,
                    itemCategoryByName,
                )
                val gross = lines.sumOf { it.lineTotal }
                val credit = transfer.creditAmountPaid ?: 0.0
                val cash = transfer.cashAmountPaid ?: 0.0
                PosSaleDetail(
                    transfer = transfer,
                    lineItems = lines,
                    grossTotal = gross.takeIf { it > 0 } ?: (credit + cash),
                    creditPaid = credit,
                    cashPaid = cash,
                )
            }

        val manual = inRange.filter { it.type == AccountTransferType.MANUAL_ADJUSTMENT }
        val shiftCredits = inRange.filter { it.type == AccountTransferType.SHIFT_CREDIT }
        val shiftReversals = inRange.filter { it.type == AccountTransferType.SHIFT_REVERSAL }

        val categoryMap = mutableMapOf<SalesCategory, Pair<Int, Double>>()
        val productMap = mutableMapOf<String, Pair<Int, Double>>()
        posSales.forEach { sale ->
            sale.lineItems.forEach { line ->
                val cat = categoryMap.getOrPut(line.category) { 0 to 0.0 }
                categoryMap[line.category] = (cat.first + line.quantity) to (cat.second + line.lineTotal)
                val prod = productMap.getOrPut(line.name) { 0 to 0.0 }
                productMap[line.name] = (prod.first + line.quantity) to (prod.second + line.lineTotal)
            }
        }

        val totalCash = posSales.sumOf { it.cashPaid }
        val totalCredit = posSales.sumOf { it.creditPaid }
        val barDiscountSavings = posSales.sumOf { sale ->
            val pct = sale.transfer.posBarDiscountPercent ?: 0
            if (pct <= 0 || sale.cashPaid <= 0) 0.0
            else sale.cashPaid * pct / (100.0 - pct).coerceAtLeast(1.0)
        }

        val typeSummaries = AccountTransferType.entries.mapNotNull { type ->
            val rows = inRange.filter { it.type == type }
            if (rows.isEmpty()) return@mapNotNull null
            TransferTypeSummary(
                type = type,
                count = rows.size,
                totalAmount = rows.sumOf { it.amount },
                creditTotal = rows.sumOf { it.creditAmountPaid ?: 0.0 },
                cashTotal = rows.sumOf { it.cashAmountPaid ?: 0.0 },
            )
        }

        return PosAccountingReport(
            period = period,
            currencyCode = currencyCode,
            generatedAtMs = generatedAtMs,
            allTransfers = inRange,
            posSales = posSales,
            manualAdjustments = manual,
            shiftCredits = shiftCredits,
            shiftReversals = shiftReversals,
            typeSummaries = typeSummaries,
            categorySummaries = categoryMap.entries
                .map { (cat, pair) -> CategorySalesSummary(cat, pair.first, pair.second) }
                .sortedByDescending { it.revenue },
            productSummaries = productMap.entries
                .map { (name, pair) -> ProductSalesSummary(name, pair.first, pair.second) }
                .sortedByDescending { it.revenue },
            totalPosSalesCount = posSales.size,
            totalCashCollected = totalCash,
            totalCreditUsed = totalCredit,
            totalManualPositive = manual.filter { it.amount > 0 }.sumOf { it.amount },
            totalManualNegative = manual.filter { it.amount < 0 }.sumOf { abs(it.amount) },
            totalShiftCredit = shiftCredits.sumOf { it.amount },
            totalShiftReversal = shiftReversals.sumOf { it.amount },
            totalBarDiscountSavings = barDiscountSavings,
        )
    }
}
