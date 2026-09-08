package com.eventmanager.app.data.utils

import com.eventmanager.app.data.models.AccountHolderKey
import com.eventmanager.app.data.models.AccountHolderType
import com.eventmanager.app.data.models.AccountTransfer
import com.eventmanager.app.data.models.AccountTransferType
import com.eventmanager.app.data.models.affectsAccountLedger
import com.eventmanager.app.data.models.PosVenueScope
import com.eventmanager.app.data.models.SalesCategory
import com.eventmanager.app.data.models.SalesSheetItem
import com.eventmanager.app.data.reports.PosItemParser
import com.eventmanager.app.data.reports.PosSaleDetail
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

data class PosSeriesPoint(
    val timestamp: Long,
    val value: Double,
)

data class PosNamedAmount(
    val key: String,
    val value: Double,
    val quantity: Int = 0,
)

data class PosCategoryProductBreakdown(
    val category: SalesCategory,
    val products: List<PosNamedAmount>,
)

data class PosCreditOnlySaleRecord(
    val holderName: String,
    val holderId: String,
    val amount: Double,
    val createdAt: Long,
)

data class PosTopCreditConsumerRecord(
    val holderName: String,
    val holderId: String,
    val holderType: AccountHolderType,
    val creditSpent: Double,
    val totalPurchases: Double,
)

data class PosHourPoint(
    val hour: Int,
    val value: Double,
)

data class RepeatCustomerSnapshot(
    val buckets: List<PosNamedAmount>,
    val returningHolders: Int,
    val totalHolders: Int,
) {
    val returnPercent: Double
        get() = if (totalHolders <= 0) 0.0 else 100.0 * returningHolders / totalHolders

    companion object {
        val EMPTY = RepeatCustomerSnapshot(emptyList(), 0, 0)
    }
}

data class PosDashboardSnapshot(
    val salesCount: List<PosSeriesPoint>,
    val creditsGrantedTotal: List<PosSeriesPoint>,
    val creditsFromShifts: List<PosSeriesPoint>,
    val creditsManual: List<PosSeriesPoint>,
    val creditUsedChf: List<PosSeriesPoint>,
    val cashPersonnelChf: List<PosSeriesPoint>,
    val revenueByCategory: List<PosNamedAmount>,
    val productsByCategory: List<PosCategoryProductBreakdown>,
    val salesByVenue: List<PosNamedAmount>,
    val salesByHolderType: List<PosNamedAmount>,
    val highestCreditOnlySale: PosCreditOnlySaleRecord?,
    val topCreditConsumer: PosTopCreditConsumerRecord?,
    val peakHourSales: List<PosHourPoint> = emptyList(),
    val shiftCreditChf: List<PosSeriesPoint> = emptyList(),
    val manualCreditChf: List<PosSeriesPoint> = emptyList(),
    val shiftReversalChf: List<PosSeriesPoint> = emptyList(),
    val barDiscountSavings: List<PosSeriesPoint> = emptyList(),
    val repeatCustomers: RepeatCustomerSnapshot = RepeatCustomerSnapshot.EMPTY,
    val volunteerBalanceBuckets: List<PosNamedAmount> = emptyList(),
    val guestBalanceBuckets: List<PosNamedAmount> = emptyList(),
) {
    companion object {
        val EMPTY = PosDashboardSnapshot(
            salesCount = emptyList(),
            creditsGrantedTotal = emptyList(),
            creditsFromShifts = emptyList(),
            creditsManual = emptyList(),
            creditUsedChf = emptyList(),
            cashPersonnelChf = emptyList(),
            revenueByCategory = emptyList(),
            productsByCategory = emptyList(),
            salesByVenue = emptyList(),
            salesByHolderType = emptyList(),
            highestCreditOnlySale = null,
            topCreditConsumer = null,
            peakHourSales = emptyList(),
            shiftCreditChf = emptyList(),
            manualCreditChf = emptyList(),
            shiftReversalChf = emptyList(),
            barDiscountSavings = emptyList(),
            repeatCustomers = RepeatCustomerSnapshot.EMPTY,
            volunteerBalanceBuckets = emptyList(),
            guestBalanceBuckets = emptyList(),
        )
    }
}

object PosDashboardStats {
    const val OTHER_PRODUCT_KEY = "__other__"
    const val TOP_PRODUCTS_PER_CATEGORY = 6

    const val REPEAT_ONE = "ONE"
    const val REPEAT_TWO_TO_FIVE = "TWO_TO_FIVE"
    const val REPEAT_SIX_TO_NINE = "SIX_TO_NINE"
    const val REPEAT_TEN_PLUS = "TEN_PLUS"

    const val BALANCE_LE_ZERO = "LE_ZERO"
    const val BALANCE_D0_10 = "D0_10"
    const val BALANCE_D10_50 = "D10_50"
    const val BALANCE_D50_PLUS = "D50_PLUS"

    val PEAK_HOURS: List<Int> = listOf(18, 19, 20, 21, 22, 23, 0, 1, 2, 3, 4, 5)

    private val zurichTimeZone: TimeZone = TimeZone.getTimeZone("Europe/Zurich")

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val WEEK_MS = 7L * DAY_MS

    fun aggregationPeriodMs(periodDays: Long): Long =
        if (periodDays in 1..30) DAY_MS else WEEK_MS

    fun periodStart(now: Long, periodDays: Long, earliestEventMs: Long?): Long {
        return if (periodDays <= 0L) {
            earliestEventMs ?: (now - 365L * DAY_MS)
        } else {
            now - periodDays * DAY_MS
        }
    }

    fun build(
        transfers: List<AccountTransfer>,
        salesItems: List<SalesSheetItem>,
        startTime: Long,
        endTime: Long,
        aggregationMs: Long,
    ): PosDashboardSnapshot {
        if (aggregationMs <= 0L) return PosDashboardSnapshot.EMPTY

        val inRange = transfers.filter { it.createdAt in startTime..endTime && it.affectsAccountLedger() }
        val (byId, byName) = PosItemParser.categoryLookups(salesItems)
        val posSales = inRange
            .filter { it.type == AccountTransferType.POS_SALE }
            .map { toSaleDetail(it, byId, byName) }
        val shiftCredits = inRange.filter { it.type == AccountTransferType.SHIFT_CREDIT }
        val manualGrants = inRange.filter { it.type == AccountTransferType.MANUAL_ADJUSTMENT && it.amount > 0.0 }
        val shiftReversals = inRange.filter { it.type == AccountTransferType.SHIFT_REVERSAL }

        val salesCount = bucketSeries(startTime, endTime, aggregationMs) { bucketStart, bucketEnd ->
            posSales.count { it.transfer.createdAt in bucketStart until bucketEnd }.toDouble()
        }
        val creditsFromShifts = bucketSeries(startTime, endTime, aggregationMs) { bucketStart, bucketEnd ->
            shiftCredits.count { it.createdAt in bucketStart until bucketEnd }.toDouble()
        }
        val creditsManual = bucketSeries(startTime, endTime, aggregationMs) { bucketStart, bucketEnd ->
            manualGrants.count { it.createdAt in bucketStart until bucketEnd }.toDouble()
        }
        val creditsGrantedTotal = creditsFromShifts.zip(creditsManual) { shift, manual ->
            PosSeriesPoint(shift.timestamp, shift.value + manual.value)
        }
        val creditUsedChf = bucketSeries(startTime, endTime, aggregationMs) { bucketStart, bucketEnd ->
            posSales.filter { it.transfer.createdAt in bucketStart until bucketEnd }.sumOf { it.creditPaid }
        }
        val cashPersonnelChf = bucketSeries(startTime, endTime, aggregationMs) { bucketStart, bucketEnd ->
            posSales.filter { it.transfer.createdAt in bucketStart until bucketEnd }.sumOf { it.cashPaid }
        }
        val shiftCreditChf = bucketSeries(startTime, endTime, aggregationMs) { bucketStart, bucketEnd ->
            shiftCredits.filter { it.createdAt in bucketStart until bucketEnd }.sumOf { it.amount }
        }
        val manualCreditChf = bucketSeries(startTime, endTime, aggregationMs) { bucketStart, bucketEnd ->
            manualGrants.filter { it.createdAt in bucketStart until bucketEnd }.sumOf { it.amount }
        }
        val shiftReversalChf = bucketSeries(startTime, endTime, aggregationMs) { bucketStart, bucketEnd ->
            shiftReversals.filter { it.createdAt in bucketStart until bucketEnd }.sumOf { abs(it.amount) }
        }
        val barDiscountSavings = bucketSeries(startTime, endTime, aggregationMs) { bucketStart, bucketEnd ->
            posSales.filter { it.transfer.createdAt in bucketStart until bucketEnd }.sumOf { saleDiscountSavings(it) }
        }
        val balances = AccountBalanceService.computeAllBalances(transfers)

        return PosDashboardSnapshot(
            salesCount = salesCount,
            creditsGrantedTotal = creditsGrantedTotal,
            creditsFromShifts = creditsFromShifts,
            creditsManual = creditsManual,
            creditUsedChf = creditUsedChf,
            cashPersonnelChf = cashPersonnelChf,
            revenueByCategory = revenueByCategory(posSales),
            productsByCategory = productsByCategory(posSales),
            salesByVenue = salesByVenue(posSales),
            salesByHolderType = salesByHolderType(posSales),
            highestCreditOnlySale = highestCreditOnlySale(posSales),
            topCreditConsumer = topCreditConsumer(posSales),
            peakHourSales = peakHourSales(posSales),
            shiftCreditChf = shiftCreditChf,
            manualCreditChf = manualCreditChf,
            shiftReversalChf = shiftReversalChf,
            barDiscountSavings = barDiscountSavings,
            repeatCustomers = repeatCustomers(posSales),
            volunteerBalanceBuckets = balanceBuckets(balances, AccountHolderType.VOLUNTEER),
            guestBalanceBuckets = balanceBuckets(balances, AccountHolderType.GUEST),
        )
    }

    internal fun saleDiscountSavings(sale: PosSaleDetail): Double {
        val pct = sale.transfer.posBarDiscountPercent ?: 0
        if (pct <= 0 || sale.cashPaid <= 0) return 0.0
        return sale.cashPaid * pct / (100.0 - pct).coerceAtLeast(1.0)
    }

    internal fun zurichHour(timestamp: Long): Int {
        val calendar = Calendar.getInstance(zurichTimeZone).apply { timeInMillis = timestamp }
        return calendar.get(Calendar.HOUR_OF_DAY)
    }

    internal fun repeatBucketKey(purchaseCount: Int): String = when {
        purchaseCount >= 10 -> REPEAT_TEN_PLUS
        purchaseCount >= 6 -> REPEAT_SIX_TO_NINE
        purchaseCount >= 2 -> REPEAT_TWO_TO_FIVE
        else -> REPEAT_ONE
    }

    internal fun balanceBucketKey(balance: Double): String = when {
        balance <= 0.0 -> BALANCE_LE_ZERO
        balance <= 10.0 -> BALANCE_D0_10
        balance <= 50.0 -> BALANCE_D10_50
        else -> BALANCE_D50_PLUS
    }

    private fun toSaleDetail(
        transfer: AccountTransfer,
        byId: Map<Long, SalesCategory>,
        byName: Map<String, SalesCategory>,
    ): PosSaleDetail {
        val lines = PosItemParser.parsePosItemsJson(transfer.posItemsJson, byId, byName)
        val credit = transfer.creditAmountPaid ?: 0.0
        val cash = transfer.cashAmountPaid ?: 0.0
        val gross = lines.sumOf { it.lineTotal }
        return PosSaleDetail(
            transfer = transfer,
            lineItems = lines,
            grossTotal = gross.takeIf { it > 0 } ?: (credit + cash),
            creditPaid = credit,
            cashPaid = cash,
        )
    }

    private fun bucketSeries(
        startTime: Long,
        endTime: Long,
        aggregationMs: Long,
        valueAt: (bucketStart: Long, bucketEnd: Long) -> Double,
    ): List<PosSeriesPoint> {
        val points = ArrayList<PosSeriesPoint>()
        var current = startTime
        var guard = 0
        while (current <= endTime && guard < 10_000) {
            points.add(PosSeriesPoint(current, valueAt(current, current + aggregationMs)))
            current += aggregationMs
            guard++
        }
        return points
    }

    private fun revenueByCategory(posSales: List<PosSaleDetail>): List<PosNamedAmount> {
        val map = linkedMapOf<SalesCategory, Pair<Int, Double>>()
        posSales.forEach { sale ->
            sale.lineItems.forEach { line ->
                val current = map.getOrPut(line.category) { 0 to 0.0 }
                map[line.category] = (current.first + line.quantity) to (current.second + line.lineTotal)
            }
        }
        return map.entries
            .filter { it.value.second > 0.0 }
            .map { (category, pair) -> PosNamedAmount(category.name, pair.second, pair.first) }
            .sortedByDescending { it.value }
    }

    private fun productsByCategory(posSales: List<PosSaleDetail>): List<PosCategoryProductBreakdown> {
        val byCategory = linkedMapOf<SalesCategory, MutableMap<String, Pair<Int, Double>>>()
        posSales.forEach { sale ->
            sale.lineItems.forEach { line ->
                val products = byCategory.getOrPut(line.category) { linkedMapOf() }
                val current = products.getOrPut(line.name) { 0 to 0.0 }
                products[line.name] = (current.first + line.quantity) to (current.second + line.lineTotal)
            }
        }
        return byCategory.entries
            .map { (category, products) ->
                val ranked = products.entries
                    .map { (name, pair) -> PosNamedAmount(name, pair.second, pair.first) }
                    .sortedByDescending { it.quantity }
                PosCategoryProductBreakdown(
                    category = category,
                    products = collapseTopProducts(ranked),
                )
            }
            .filter { breakdown -> breakdown.products.any { it.quantity > 0 } }
            .sortedBy { it.category.name }
    }

    private fun collapseTopProducts(ranked: List<PosNamedAmount>): List<PosNamedAmount> {
        if (ranked.size <= TOP_PRODUCTS_PER_CATEGORY) return ranked
        val top = ranked.take(TOP_PRODUCTS_PER_CATEGORY)
        val rest = ranked.drop(TOP_PRODUCTS_PER_CATEGORY)
        val other = PosNamedAmount(
            key = OTHER_PRODUCT_KEY,
            value = rest.sumOf { it.value },
            quantity = rest.sumOf { it.quantity },
        )
        return if (other.quantity > 0) top + other else top
    }

    private fun salesByVenue(posSales: List<PosSaleDetail>): List<PosNamedAmount> {
        val map = linkedMapOf<String, Int>()
        posSales.forEach { sale ->
            val venue = sale.transfer.posVenueName.trim().ifBlank { PosVenueScope.GLOBAL }
            map[venue] = (map[venue] ?: 0) + 1
        }
        return map.entries
            .map { (venue, count) -> PosNamedAmount(venue, count.toDouble(), count) }
            .sortedByDescending { it.quantity }
    }

    private fun salesByHolderType(posSales: List<PosSaleDetail>): List<PosNamedAmount> {
        val map = linkedMapOf<AccountHolderType, Int>()
        posSales.forEach { sale ->
            map[sale.transfer.holderType] = (map[sale.transfer.holderType] ?: 0) + 1
        }
        return map.entries
            .map { (type, count) -> PosNamedAmount(type.name, count.toDouble(), count) }
            .sortedByDescending { it.quantity }
    }

    private fun highestCreditOnlySale(posSales: List<PosSaleDetail>): PosCreditOnlySaleRecord? {
        return posSales
            .filter { it.cashPaid <= 0.0 && it.creditPaid > 0.0 }
            .maxWithOrNull(compareBy<PosSaleDetail> { it.creditPaid }.thenBy { it.transfer.createdAt })
            ?.let { sale ->
                PosCreditOnlySaleRecord(
                    holderName = sale.transfer.holderName,
                    holderId = sale.transfer.holderId,
                    amount = sale.creditPaid,
                    createdAt = sale.transfer.createdAt,
                )
            }
    }

    private fun topCreditConsumer(posSales: List<PosSaleDetail>): PosTopCreditConsumerRecord? {
        data class Acc(var name: String, val holderId: String, val holderType: AccountHolderType, var credit: Double, var total: Double)
        val byHolder = linkedMapOf<String, Acc>()
        posSales.forEach { sale ->
            val key = "${sale.transfer.holderType.name}:${sale.transfer.holderId}"
            val acc = byHolder.getOrPut(key) {
                Acc(sale.transfer.holderName, sale.transfer.holderId, sale.transfer.holderType, 0.0, 0.0)
            }
            acc.name = sale.transfer.holderName
            acc.credit += sale.creditPaid
            acc.total += sale.creditPaid + sale.cashPaid
        }
        return byHolder.values
            .filter { it.credit > 0.0 }
            .maxWithOrNull(compareBy<Acc> { it.credit }.thenBy { it.total })
            ?.let { acc ->
                PosTopCreditConsumerRecord(
                    holderName = acc.name,
                    holderId = acc.holderId,
                    holderType = acc.holderType,
                    creditSpent = acc.credit,
                    totalPurchases = acc.total,
                )
            }
    }

    private fun peakHourSales(posSales: List<PosSaleDetail>): List<PosHourPoint> {
        val counts = PEAK_HOURS.associateWith { 0 }.toMutableMap()
        posSales.forEach { sale ->
            val hour = zurichHour(sale.transfer.createdAt)
            if (hour in counts) {
                counts[hour] = (counts[hour] ?: 0) + 1
            }
        }
        return PEAK_HOURS.map { hour -> PosHourPoint(hour, (counts[hour] ?: 0).toDouble()) }
    }

    private fun repeatCustomers(posSales: List<PosSaleDetail>): RepeatCustomerSnapshot {
        val counts = linkedMapOf(
            REPEAT_ONE to 0,
            REPEAT_TWO_TO_FIVE to 0,
            REPEAT_SIX_TO_NINE to 0,
            REPEAT_TEN_PLUS to 0,
        )
        val byHolder = posSales.groupingBy { it.transfer.holderId }.eachCount()
        var returning = 0
        byHolder.values.forEach { purchaseCount ->
            val key = repeatBucketKey(purchaseCount)
            counts[key] = (counts[key] ?: 0) + 1
            if (purchaseCount >= 2) returning++
        }
        return RepeatCustomerSnapshot(
            buckets = counts.map { (key, count) -> PosNamedAmount(key, count.toDouble(), count) },
            returningHolders = returning,
            totalHolders = byHolder.size,
        )
    }

    private fun balanceBuckets(
        balances: Map<AccountHolderKey, Double>,
        holderType: AccountHolderType,
    ): List<PosNamedAmount> {
        val counts = linkedMapOf(
            BALANCE_LE_ZERO to 0,
            BALANCE_D0_10 to 0,
            BALANCE_D10_50 to 0,
            BALANCE_D50_PLUS to 0,
        )
        balances.forEach { (key, balance) ->
            if (key.holderType != holderType) return@forEach
            val bucket = balanceBucketKey(balance)
            counts[bucket] = (counts[bucket] ?: 0) + 1
        }
        return counts.map { (key, count) -> PosNamedAmount(key, count.toDouble(), count) }
    }
}
