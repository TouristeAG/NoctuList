package com.eventmanager.app.data.utils

import com.eventmanager.app.data.models.AccountHolderType
import com.eventmanager.app.data.models.AccountTransfer
import com.eventmanager.app.data.models.AccountTransferSyncState
import com.eventmanager.app.data.models.AccountTransferType
import com.eventmanager.app.data.models.PosVenueScope
import com.eventmanager.app.data.models.SalesSheetItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PosDashboardStatsTest {

    private val dayMs = 24L * 60 * 60 * 1000
    private val now = 1_700_000_000_000L
    private val start = now - 7 * dayMs
    private val aggregation = dayMs

    private fun transfer(
        type: AccountTransferType,
        createdAt: Long,
        amount: Double = 0.0,
        credit: Double? = null,
        cash: Double? = null,
        holderType: AccountHolderType = AccountHolderType.VOLUNTEER,
        holderId: String = "v1",
        holderName: String = "Alice",
        posItemsJson: String = "",
        posVenueName: String = "Main",
        sourceReference: String = "ref-$createdAt-$type-$holderId",
        posBarDiscountPercent: Int? = null,
    ) = AccountTransfer(
        holderType = holderType,
        holderId = holderId,
        holderName = holderName,
        amount = amount,
        type = type,
        sourceReference = sourceReference,
        creditAmountPaid = credit,
        cashAmountPaid = cash,
        posItemsJson = posItemsJson,
        posVenueName = posVenueName,
        posBarDiscountPercent = posBarDiscountPercent,
        createdAt = createdAt,
    )

    private val beer = SalesSheetItem(id = 1, name = "Beer", price = 5.0, categories = "BAR")
    private val ticket = SalesSheetItem(id = 2, name = "Ticket", price = 20.0, categories = "ENTRY")

    @Test
    fun salesCountAndCashCreditSeriesMatchBuckets() {
        val t1 = start + dayMs + 1_000
        val t2 = start + 2 * dayMs + 1_000
        val transfers = listOf(
            transfer(
                type = AccountTransferType.POS_SALE,
                createdAt = t1,
                credit = 10.0,
                cash = 5.0,
                posItemsJson = "1:Beer:5.0:2",
            ),
            transfer(
                type = AccountTransferType.POS_SALE,
                createdAt = t2,
                credit = 20.0,
                cash = 0.0,
                posItemsJson = "2:Ticket:20.0:1",
                holderId = "g1",
                holderType = AccountHolderType.GUEST,
                holderName = "Bob",
            ),
        )

        val stats = PosDashboardStats.build(transfers, listOf(beer, ticket), start, now, aggregation)

        assertEquals(2.0, stats.salesCount.sumOf { it.value })
        assertEquals(30.0, stats.creditUsedChf.sumOf { it.value })
        assertEquals(5.0, stats.cashPersonnelChf.sumOf { it.value })
        val firstBucket = stats.salesCount.first { it.timestamp == start + dayMs }
        assertEquals(1.0, firstBucket.value)
    }

    @Test
    fun creditsGrantedCountShiftAndManualSeparately() {
        val t1 = start + dayMs + 500
        val transfers = listOf(
            transfer(type = AccountTransferType.SHIFT_CREDIT, createdAt = t1, amount = 10.0),
            transfer(
                type = AccountTransferType.SHIFT_CREDIT,
                createdAt = t1 + 10,
                amount = 5.0,
                sourceReference = "shift-2",
            ),
            transfer(type = AccountTransferType.MANUAL_ADJUSTMENT, createdAt = t1 + 20, amount = 8.0),
            transfer(
                type = AccountTransferType.MANUAL_ADJUSTMENT,
                createdAt = t1 + 30,
                amount = -3.0,
                sourceReference = "manual-neg",
            ),
        )

        val stats = PosDashboardStats.build(transfers, emptyList(), start, now, aggregation)
        assertEquals(2.0, stats.creditsFromShifts.sumOf { it.value })
        assertEquals(1.0, stats.creditsManual.sumOf { it.value })
        assertEquals(3.0, stats.creditsGrantedTotal.sumOf { it.value })
    }

    @Test
    fun piesSplitCategoryVenueAndHolder() {
        val t1 = start + dayMs + 1_000
        val transfers = listOf(
            transfer(
                type = AccountTransferType.POS_SALE,
                createdAt = t1,
                credit = 10.0,
                cash = 0.0,
                posItemsJson = "1:Beer:5.0:2",
                posVenueName = "Main",
            ),
            transfer(
                type = AccountTransferType.POS_SALE,
                createdAt = t1 + 10,
                credit = 20.0,
                cash = 5.0,
                posItemsJson = "2:Ticket:20.0:1",
                holderType = AccountHolderType.GUEST,
                holderId = "g1",
                holderName = "Bob",
                posVenueName = "",
                sourceReference = "sale-2",
            ),
        )

        val stats = PosDashboardStats.build(transfers, listOf(beer, ticket), start, now, aggregation)
        assertEquals(10.0, stats.revenueByCategory.first { it.key == "BAR" }.value)
        assertEquals(20.0, stats.revenueByCategory.first { it.key == "ENTRY" }.value)
        assertEquals(1, stats.salesByVenue.first { it.key == "Main" }.quantity)
        assertEquals(1, stats.salesByVenue.first { it.key == PosVenueScope.GLOBAL }.quantity)
        assertEquals(1, stats.salesByHolderType.first { it.key == "VOLUNTEER" }.quantity)
        assertEquals(1, stats.salesByHolderType.first { it.key == "GUEST" }.quantity)

        val barProducts = stats.productsByCategory.first { it.category.name == "BAR" }.products
        assertEquals("Beer", barProducts.first().key)
        assertEquals(2, barProducts.first().quantity)
    }

    @Test
    fun recordsPickHighestCreditOnlySaleAndTopConsumer() {
        val t1 = start + dayMs + 1_000
        val transfers = listOf(
            transfer(
                type = AccountTransferType.POS_SALE,
                createdAt = t1,
                credit = 40.0,
                cash = 0.0,
                holderId = "v1",
                holderName = "Alice",
                posItemsJson = "1:Beer:5.0:8",
            ),
            transfer(
                type = AccountTransferType.POS_SALE,
                createdAt = t1 + 10,
                credit = 50.0,
                cash = 10.0,
                holderId = "v1",
                holderName = "Alice",
                posItemsJson = "2:Ticket:20.0:3",
                sourceReference = "sale-mix",
            ),
            transfer(
                type = AccountTransferType.POS_SALE,
                createdAt = t1 + 20,
                credit = 30.0,
                cash = 0.0,
                holderType = AccountHolderType.GUEST,
                holderId = "g1",
                holderName = "Bob",
                posItemsJson = "2:Ticket:20.0:1",
                sourceReference = "sale-bob",
            ),
        )

        val stats = PosDashboardStats.build(transfers, listOf(beer, ticket), start, now, aggregation)
        val creditOnly = assertNotNull(stats.highestCreditOnlySale)
        assertEquals("Alice", creditOnly.holderName)
        assertEquals(40.0, creditOnly.amount)

        val consumer = assertNotNull(stats.topCreditConsumer)
        assertEquals("Alice", consumer.holderName)
        assertEquals(90.0, consumer.creditSpent)
        assertEquals(100.0, consumer.totalPurchases)
    }

    @Test
    fun emptyTransfersYieldEmptyRecordsAndZeroSeries() {
        val stats = PosDashboardStats.build(emptyList(), emptyList(), start, now, aggregation)
        assertEquals(true, stats.salesCount.isNotEmpty())
        assertEquals(0.0, stats.salesCount.sumOf { it.value })
        assertNull(stats.highestCreditOnlySale)
        assertNull(stats.topCreditConsumer)
        assertEquals(emptyList(), stats.revenueByCategory)
    }

    @Test
    fun productsBeyondTopSixCollapseIntoOther() {
        val t1 = start + dayMs + 1_000
        val items = (1..8).map { index ->
            SalesSheetItem(id = index.toLong(), name = "Drink$index", price = 1.0, categories = "BAR")
        }
        val json = items.joinToString("|") { "${it.id}:${it.name}:1.0:1" }
        val transfers = listOf(
            transfer(
                type = AccountTransferType.POS_SALE,
                createdAt = t1,
                credit = 8.0,
                posItemsJson = json,
            ),
        )
        val stats = PosDashboardStats.build(transfers, items, start, now, aggregation)
        val bar = stats.productsByCategory.single()
        assertEquals(PosDashboardStats.TOP_PRODUCTS_PER_CATEGORY + 1, bar.products.size)
        val other = bar.products.last()
        assertEquals(PosDashboardStats.OTHER_PRODUCT_KEY, other.key)
        assertEquals(2, other.quantity)
    }

    @Test
    fun peakHoursUseZurichOvernightAxis() {
        val evening = atZurichHour(now - 2 * dayMs, 22)
        val morning = atZurichHour(now - 2 * dayMs, 2)
        val afternoon = atZurichHour(now - 2 * dayMs, 14)
        val transfers = listOf(
            transfer(type = AccountTransferType.POS_SALE, createdAt = evening, credit = 5.0),
            transfer(
                type = AccountTransferType.POS_SALE,
                createdAt = morning,
                credit = 5.0,
                holderId = "v2",
                sourceReference = "sale-morning",
            ),
            transfer(
                type = AccountTransferType.POS_SALE,
                createdAt = afternoon,
                credit = 5.0,
                holderId = "v3",
                sourceReference = "sale-afternoon",
            ),
        )
        val stats = PosDashboardStats.build(transfers, emptyList(), start, now, aggregation)
        assertEquals(PosDashboardStats.PEAK_HOURS, stats.peakHourSales.map { it.hour })
        assertEquals(1.0, stats.peakHourSales.first { it.hour == 22 }.value)
        assertEquals(1.0, stats.peakHourSales.first { it.hour == 2 }.value)
        assertEquals(0.0, stats.peakHourSales.first { it.hour == 18 }.value)
        assertEquals(2.0, stats.peakHourSales.sumOf { it.value })
    }

    @Test
    fun creditChfSeriesAndBarDiscountMatchAccountingFormula() {
        val t1 = start + dayMs + 500
        val transfers = listOf(
            transfer(type = AccountTransferType.SHIFT_CREDIT, createdAt = t1, amount = 12.5),
            transfer(type = AccountTransferType.MANUAL_ADJUSTMENT, createdAt = t1 + 10, amount = 8.0),
            transfer(type = AccountTransferType.MANUAL_ADJUSTMENT, createdAt = t1 + 20, amount = -2.0, sourceReference = "neg"),
            transfer(type = AccountTransferType.SHIFT_REVERSAL, createdAt = t1 + 30, amount = -4.0),
            transfer(
                type = AccountTransferType.POS_SALE,
                createdAt = t1 + 40,
                cash = 9.0,
                posBarDiscountPercent = 10,
                posItemsJson = "1:Beer:5.0:1",
            ),
        )
        val stats = PosDashboardStats.build(transfers, listOf(beer), start, now, aggregation)
        assertEquals(12.5, stats.shiftCreditChf.sumOf { it.value })
        assertEquals(8.0, stats.manualCreditChf.sumOf { it.value })
        assertEquals(4.0, stats.shiftReversalChf.sumOf { it.value })
        assertEquals(1.0, stats.barDiscountSavings.sumOf { it.value }, 1e-9)
    }

    @Test
    fun repeatCustomersBucketAndReturnPercent() {
        val t1 = start + dayMs + 1_000
        val transfers = (1..10).map { index ->
            transfer(
                type = AccountTransferType.POS_SALE,
                createdAt = t1 + index,
                holderId = "alice",
                credit = 1.0,
                sourceReference = "alice-$index",
            )
        } + listOf(
            transfer(type = AccountTransferType.POS_SALE, createdAt = t1 + 20, holderId = "bob", credit = 1.0, sourceReference = "bob-1"),
            transfer(type = AccountTransferType.POS_SALE, createdAt = t1 + 21, holderId = "bob", credit = 1.0, sourceReference = "bob-2"),
            transfer(type = AccountTransferType.POS_SALE, createdAt = t1 + 22, holderId = "cara", credit = 1.0, sourceReference = "cara-1"),
        )
        val stats = PosDashboardStats.build(transfers, emptyList(), start, now, aggregation)
        val byKey = stats.repeatCustomers.buckets.associate { it.key to it.quantity }
        assertEquals(1, byKey[PosDashboardStats.REPEAT_ONE])
        assertEquals(1, byKey[PosDashboardStats.REPEAT_TWO_TO_FIVE])
        assertEquals(1, byKey[PosDashboardStats.REPEAT_TEN_PLUS])
        assertEquals(3, stats.repeatCustomers.totalHolders)
        assertEquals(2, stats.repeatCustomers.returningHolders)
        assertEquals(2.0 / 3.0 * 100.0, stats.repeatCustomers.returnPercent, 1e-9)
    }

    @Test
    fun balanceBucketsSplitVolunteersAndGuests() {
        val t1 = start + dayMs + 1_000
        val transfers = listOf(
            transfer(type = AccountTransferType.SHIFT_CREDIT, createdAt = t1, holderId = "v-neg", amount = -2.0),
            transfer(type = AccountTransferType.SHIFT_CREDIT, createdAt = t1 + 1, holderId = "v-small", amount = 5.0),
            transfer(type = AccountTransferType.SHIFT_CREDIT, createdAt = t1 + 2, holderId = "v-mid", amount = 25.0),
            transfer(type = AccountTransferType.SHIFT_CREDIT, createdAt = t1 + 3, holderId = "v-big", amount = 80.0),
            transfer(
                type = AccountTransferType.MANUAL_ADJUSTMENT,
                createdAt = t1 + 4,
                holderType = AccountHolderType.GUEST,
                holderId = "g1",
                amount = 3.0,
            ),
        )
        val stats = PosDashboardStats.build(transfers, emptyList(), start, now, aggregation)
        val volunteers = stats.volunteerBalanceBuckets.associate { it.key to it.quantity }
        val guests = stats.guestBalanceBuckets.associate { it.key to it.quantity }
        assertEquals(1, volunteers[PosDashboardStats.BALANCE_LE_ZERO])
        assertEquals(1, volunteers[PosDashboardStats.BALANCE_D0_10])
        assertEquals(1, volunteers[PosDashboardStats.BALANCE_D10_50])
        assertEquals(1, volunteers[PosDashboardStats.BALANCE_D50_PLUS])
        assertEquals(1, guests[PosDashboardStats.BALANCE_D0_10])
        assertEquals(0, guests[PosDashboardStats.BALANCE_LE_ZERO])
    }

    @Test
    fun rejectedPosSalesAreExcludedFromCountsAndBalances() {
        val t1 = start + dayMs + 1_000
        val confirmed = transfer(
            type = AccountTransferType.POS_SALE,
            createdAt = t1,
            amount = -10.0,
            credit = 10.0,
            posItemsJson = "1:Beer:5.0:2",
        )
        val rejected = confirmed.copy(
            sourceReference = "rejected-sale",
            syncState = AccountTransferSyncState.REJECTED,
        )
        val snapshot = PosDashboardStats.build(
            transfers = listOf(confirmed, rejected),
            salesItems = listOf(beer),
            startTime = start,
            endTime = now,
            aggregationMs = aggregation,
        )
        val bucket = snapshot.salesCount.first { it.value > 0.0 }
        assertEquals(1.0, bucket.value)
        val balance = AccountBalanceService.computeBalance(
            AccountHolderType.VOLUNTEER,
            "v1",
            listOf(confirmed, rejected),
        )
        assertEquals(-10.0, balance)
    }

    private fun atZurichHour(around: Long, hour: Int): Long {
        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Europe/Zurich"))
        calendar.timeInMillis = around
        calendar.set(java.util.Calendar.HOUR_OF_DAY, hour)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
}
