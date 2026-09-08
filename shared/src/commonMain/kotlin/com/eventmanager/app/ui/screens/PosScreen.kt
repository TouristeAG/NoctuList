package com.eventmanager.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.eventmanager.app.data.models.*
import com.eventmanager.app.data.remote.BackendType
import com.eventmanager.app.data.remote.MultiOrgMerge
import com.eventmanager.app.data.nfc.NfcUid
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.data.utils.DepositReturnPolicy
import com.eventmanager.app.data.utils.PosCartLine
import com.eventmanager.app.data.utils.PosPaymentBreakdown
import com.eventmanager.app.data.utils.computePosPayment
import com.eventmanager.app.data.utils.depositRefusalMessage
import com.eventmanager.app.data.utils.formatMoney
import com.eventmanager.app.platform.LocalPlatformContext
import com.eventmanager.app.platform.NfcInputAvailability
import com.eventmanager.app.platform.createCardReaderService
import com.eventmanager.app.platform.isDesktop
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import com.eventmanager.app.ui.components.*
import com.eventmanager.app.ui.utils.*
import com.eventmanager.app.ui.platform.NfcUidListenerEffect
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

data class PosCartEntry(
    val line: PosCartLine,
    val key: String = "${line.itemId ?: "m"}:${line.name}:${System.nanoTime()}"
)

internal data class PosItemGridSpec(
    val columns: Int,
    val tileHeight: Dp,
    val largeTiles: Boolean,
    val scrollEnabled: Boolean,
)

private val PosGridGap = 8.dp
private val PosGridContentPadding = 4.dp
private val PosMinCellHeightFloor = 104.dp
private val PosMaxCellHeight = 210.dp
private val PosCellAspectRatio = 0.9f
private val PosLargeTileMinHeight = 150.dp
private val PosLargeTileMinWidth = 156.dp

/**
 * Comfortable tile width for the device class the grid is being laid out on. A phone pane can
 * only afford small tiles, a desktop or tablet pane fits many more products per row.
 */
private fun posTargetCellWidth(availableWidth: Dp): Dp = when {
    availableWidth < 380.dp -> 132.dp
    availableWidth < 620.dp -> 138.dp
    availableWidth < 900.dp -> 146.dp
    availableWidth < 1300.dp -> 158.dp
    else -> 172.dp
}

/** Hard ceiling per device class so a very wide pane does not end up with unreadable slivers. */
private fun posMaxColumns(availableWidth: Dp): Int = when {
    availableWidth < 380.dp -> 2
    availableWidth < 620.dp -> 4
    availableWidth < 900.dp -> 5
    availableWidth < 1300.dp -> 7
    else -> 9
}

/**
 * Picks column count + tile height for the product grid.
 *
 * Columns come from the available width alone, so a long catalogue always fills the pane
 * horizontally instead of collapsing into a single tall column. Height then either stretches
 * to fill the viewport (when every product fits) or falls back to a width-derived tile height
 * and scrolls. When the grid fits with room to spare, fewer columns are preferred if that
 * lets the tiles grow into the leftover vertical space rather than leaving it empty.
 */
internal fun resolvePosItemGridSpec(
    availableWidth: Dp,
    availableHeight: Dp,
    cellCount: Int,
    gap: Dp = PosGridGap,
    contentPadding: Dp = PosGridContentPadding,
): PosItemGridSpec {
    val count = cellCount.coerceAtLeast(1)
    val usableWidth = (availableWidth - contentPadding * 2).coerceAtLeast(1.dp)
    val usableHeight = (availableHeight - contentPadding * 2).coerceAtLeast(1.dp)

    val targetWidth = posTargetCellWidth(availableWidth)
    val densestColumns = ((usableWidth + gap) / (targetWidth + gap))
        .toInt()
        .coerceIn(1, posMaxColumns(availableWidth))
        .coerceAtMost(count)

    data class Candidate(
        val columns: Int,
        val cellWidth: Dp,
        val tileHeight: Dp,
        val scrollEnabled: Boolean,
        val unusedHeight: Dp,
    )

    fun candidateFor(columns: Int): Candidate {
        val rows = (count + columns - 1) / columns
        val hGaps = gap * (columns - 1)
        val vGaps = gap * (rows - 1)
        val cellWidth = (usableWidth - hGaps) / columns
        val naturalHeight = (cellWidth * PosCellAspectRatio)
            .coerceIn(PosMinCellHeightFloor, PosMaxCellHeight)
        val fits = naturalHeight * rows + vGaps <= usableHeight
        val tileHeight = if (fits) {
            ((usableHeight - vGaps) / rows).coerceIn(naturalHeight, PosMaxCellHeight)
        } else {
            naturalHeight
        }
        return Candidate(
            columns = columns,
            cellWidth = cellWidth,
            tileHeight = tileHeight,
            scrollEnabled = !fits,
            unusedHeight = (usableHeight - (tileHeight * rows + vGaps)).coerceAtLeast(0.dp),
        )
    }

    val densest = candidateFor(densestColumns)
    // Only reshuffle columns when everything already fits: narrowing a scrolling grid would
    // undo the density we just gained.
    val chosen = if (densest.scrollEnabled) {
        densest
    } else {
        (densestColumns downTo 1)
            .map(::candidateFor)
            .filter { !it.scrollEnabled }
            .minWithOrNull(compareBy({ it.unusedHeight.value }, { -it.columns }))
            ?: densest
    }

    return PosItemGridSpec(
        columns = chosen.columns,
        tileHeight = chosen.tileHeight,
        largeTiles = chosen.tileHeight >= PosLargeTileMinHeight && chosen.cellWidth >= PosLargeTileMinWidth,
        scrollEnabled = chosen.scrollEnabled,
    )
}

private fun SalesSheetItem.isBarDiscountEligible(): Boolean {
    // A deposit is money held, not drink sold: discounting the purchase but refunding the full
    // price on return would make the pair asymmetric.
    if (isDeposit || price < 0.0) return false
    val categories = SalesCategory.parseList(this.categories)
    return hasDiscount ||
        categories.contains(SalesCategory.BAR) ||
        categories.isEmpty()
}

private fun resolvePaymentCartLines(
    cart: List<PosCartEntry>,
    salesItems: List<SalesSheetItem>,
    barDiscountPercent: Int,
    selectedCategory: SalesCategory?,
): List<PosCartLine> {
    val discountContext = barDiscountPercent > 0
    return cart.map { entry ->
        val line = entry.line
        val catalogItem = line.itemId?.let { id -> salesItems.find { it.id == id } }
        if (line.unitPrice < 0.0 || catalogItem?.isDeposit == true) {
            return@map line.copy(barDiscountEligible = false)
        }
        val fromCatalog = catalogItem?.isBarDiscountEligible() ?: false
        val manualEligible = line.itemId == null && discountContext
        val barFilterEligible = discountContext && selectedCategory == SalesCategory.BAR
        line.copy(
            barDiscountEligible = line.barDiscountEligible ||
                fromCatalog ||
                manualEligible ||
                barFilterEligible
        )
    }
}

private fun addLineToCart(cart: List<PosCartEntry>, line: PosCartLine): List<PosCartEntry> {
    if (line.itemId != null) {
        val index = cart.indexOfFirst { it.line.itemId == line.itemId }
        if (index >= 0) {
            val existing = cart[index]
            return cart.toMutableList().apply {
                this[index] = existing.copy(
                    line = existing.line.copy(quantity = existing.line.quantity + 1)
                )
            }
        }
    }
    return cart + PosCartEntry(line)
}

/** Transient red banner under the customer card: unreadable badge, or a refused cart action. */
private sealed interface PosScanFeedback {
    data object Unknown : PosScanFeedback
    data class Refused(val message: String) : PosScanFeedback
}

private data class PosPendingProfileSwitch(
    val volunteer: Volunteer?,
    val guest: Guest?,
    val displayName: String,
)

private val PosCardShape = RoundedCornerShape(16.dp)
private val PosTileShape = RoundedCornerShape(14.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosScreen(
    viewModel: EventManagerViewModel,
    salesItems: List<SalesSheetItem>,
    volunteers: List<Volunteer>,
    guests: List<Guest>,
    venues: List<VenueEntity> = emptyList(),
    onBack: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
) {
    val platformContext = LocalPlatformContext.current
    val isDesktop = platformContext.isDesktop
    val settingsManager = remember(platformContext) { SettingsManager(platformContext) }
    val currencyCode = remember { settingsManager.getCurrencyCode() }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    var selectedCategory by remember {
        mutableStateOf(
            settingsManager.getPosSelectedCategoryName()?.let { name ->
                runCatching { SalesCategory.valueOf(name) }.getOrNull()
            }
        )
    }
    val activeVenueNames = remember(venues) { venues.filter { it.isActive }.map { it.name } }
    var selectedVenue by remember(activeVenueNames) {
        mutableStateOf(
            PosVenueScope.normalizeSelectedVenue(settingsManager.getPosSelectedVenue(), activeVenueNames)
        )
    }
    var customerVolunteer by remember { mutableStateOf<Volunteer?>(null) }
    var customerGuest by remember { mutableStateOf<Guest?>(null) }
    var customerOrgId by remember { mutableStateOf("") }
    var cart by remember { mutableStateOf<List<PosCartEntry>>(emptyList()) }
    var showQr by remember { mutableStateOf(false) }
    var showManualAmount by remember { mutableStateOf(false) }
    val saleUiResult by viewModel.posSaleUiResult.collectAsState()
    val isProcessing by viewModel.posSaleInFlight.collectAsState()
    val showResult = saleUiResult?.let { result ->
        if (result.success) PosResultState.Success(result) else PosResultState.Failure(result.message)
    }
    var readerStatus by remember { mutableStateOf<String?>(null) }
    var scanFeedback by remember { mutableStateOf<PosScanFeedback?>(null) }
    /** Bumped on every feedback banner so an identical message replays its entry animation. */
    var feedbackNonce by remember { mutableIntStateOf(0) }
    var scanFlashNonce by remember { mutableIntStateOf(0) }
    var pendingProfileSwitch by remember { mutableStateOf<PosPendingProfileSwitch?>(null) }
    var pendingCashConfirmation by remember { mutableStateOf(false) }
    /** Frozen amounts for the cash dialog — live [paymentPreview] mutates after the sale debits credit. */
    var cashConfirmSnapshot by remember { mutableStateOf<PosPaymentBreakdown?>(null) }
    var cashConfirmBarDiscount by remember { mutableIntStateOf(0) }

    val cardReader = remember(platformContext) { createCardReaderService(platformContext) }
    var nfcAvailability by remember { mutableStateOf(cardReader.getNfcInputAvailability()) }

    LaunchedEffect(platformContext) {
        while (true) {
            nfcAvailability = withContext(Dispatchers.IO) {
                cardReader.refreshConnectionState()
                cardReader.getNfcInputAvailability()
            }
            delay(800)
        }
    }

    val hasCustomer = customerVolunteer != null || customerGuest != null

    val manualDefaultLabel = stringResource(Res.string.pos_manual_default_label)

    val temporaryGuestFeatures = rememberTemporaryGuestFeatures(viewModel)
    /**
     * Temporary guests reach the till only when they actually have something to spend there — a
     * credit account or a bar discount. Otherwise they would just clutter the customer search.
     */
    val permanentGuests = remember(guests, temporaryGuestFeatures) {
        guests.filter {
            !it.isVolunteerBenefit && (
                !it.isTemporaryGuest || (
                    temporaryGuestFeatures.enabled &&
                        (temporaryGuestFeatures.creditsEnabled || it.barDiscountPercent > 0)
                    )
                )
        }
    }

    val isCompact = isCompactScreen()
    val padding = getResponsivePadding()
    val posAnimatedBackground = BackgroundAnimationStyle.isEnabled(
        rememberPosBackgroundAnimationStyle(settingsManager)
    )

    val isAllOrgsMode = viewModel.isFirebaseAllOrgsMode()
    val mergedPosProducts = remember(salesItems, isAllOrgsMode) {
        if (isAllOrgsMode) MultiOrgMerge.mergePosProducts(salesItems) else emptyList()
    }

    val filteredItems = remember(salesItems, mergedPosProducts, selectedCategory, selectedVenue, isAllOrgsMode) {
        val baseItems = if (isAllOrgsMode) {
            mergedPosProducts.map { it.displayItem }
        } else {
            salesItems.filter { it.isActive }
        }
        baseItems.filter { item ->
            PosVenueScope.isItemAvailableAt(
                PosVenueScope.parseVenueList(item.availableVenues),
                selectedVenue,
            )
        }.filter { item ->
            if (selectedCategory == null) true
            else SalesCategory.parseList(item.categories).contains(selectedCategory)
        }.sortedBy { it.name.lowercase() }
    }

    LaunchedEffect(selectedCategory) {
        settingsManager.setPosSelectedCategoryName(selectedCategory?.name)
    }

    val subcategoryCatalog by viewModel.posSubcategories.collectAsState()
    var selectedSubcategory by remember { mutableStateOf<String?>(null) }
    // Only sub-categories that actually hold a product in the current category + venue scope,
    // so the bar disappears entirely when the org has not set any up.
    val subcategoryOptions = remember(subcategoryCatalog, filteredItems, selectedCategory) {
        selectedCategory?.let { category ->
            PosSubcategoryCatalog.visibleFor(subcategoryCatalog, category, filteredItems)
        }.orEmpty()
    }
    LaunchedEffect(selectedCategory, subcategoryOptions) {
        if (subcategoryOptions.none { it.name.equals(selectedSubcategory, ignoreCase = true) }) {
            selectedSubcategory = null
        }
    }
    // Raw templates rather than stringResource(res, arg): the substitution happens once per
    // deposit product, and a composable call inside that loop would be unstable.
    val depositReturnNameFormat = stringResource(Res.string.pos_deposit_return_name)
    val depositReturnNameCapsFormat = stringResource(Res.string.pos_deposit_return_name_caps)
    val visibleItems = remember(
        filteredItems,
        selectedSubcategory,
        depositReturnNameFormat,
        depositReturnNameCapsFormat,
    ) {
        PosDeposit.expandForPos(
            filteredItems.filter { it.matchesSubcategory(selectedSubcategory) },
        ) { item ->
            PosDeposit.returnNameFor(item, depositReturnNameFormat, depositReturnNameCapsFormat)
        }
    }

    val accountBalances by viewModel.accountBalances.collectAsState()
    val allTransfers by viewModel.accountTransfers.collectAsState()
    val customerTransfers = remember(allTransfers, customerVolunteer, customerGuest) {
        val holderId = customerVolunteer?.id ?: customerGuest?.nanoId
        val holderType = if (customerVolunteer != null) {
            AccountHolderType.VOLUNTEER
        } else {
            AccountHolderType.GUEST
        }
        if (holderId == null) {
            emptyList()
        } else {
            allTransfers.filter { it.holderType == holderType && it.holderId == holderId }
        }
    }

    val customerBalance = when {
        customerVolunteer != null -> viewModel.balanceForHolder(
            AccountHolderType.VOLUNTEER,
            customerVolunteer!!.id,
            customerOrgId,
        )
        customerGuest != null -> viewModel.balanceForHolder(
            AccountHolderType.GUEST,
            customerGuest!!.nanoId,
            customerOrgId,
        )
        else -> 0.0
    }

    val volunteerActiveBarDiscount = remember(customerVolunteer, viewModel.jobs.value, viewModel.jobTypeConfigs.value) {
        if (customerVolunteer == null) 0
        else {
            val jobs = viewModel.jobs.value.filter { it.volunteerId == customerVolunteer!!.id }
            val configs = viewModel.jobTypeConfigs.value
            BenefitCalculator.calculateVolunteerBenefitStatus(
                customerVolunteer!!, jobs, configs,
                offsetHours = settingsManager.getDateChangeOffsetHours()
            ).benefits.barDiscount
        }
    }
    val isFirebaseBackend = viewModel.getActiveBackendType() == BackendType.FIREBASE
    val paymentBarDiscount = if (customerVolunteer != null) {
        volunteerActiveBarDiscount
    } else {
        // The selected guest is a snapshot; read the discount off the live row so an admin edit
        // mid-session is honoured, exactly like the recomputed volunteer benefit above.
        customerGuest
            ?.let { selected -> permanentGuests.find { it.nanoId == selected.nanoId } ?: selected }
            ?.activeBarDiscountPercent(isFirebaseBackend)
            ?: 0
    }
    val paymentCartLines = resolvePaymentCartLines(
        cart = cart,
        salesItems = salesItems,
        barDiscountPercent = paymentBarDiscount,
        selectedCategory = selectedCategory,
    )
    val cartShowsBarDiscount = paymentBarDiscount > 0 && paymentCartLines.any { it.barDiscountEligible }
    val purchaseCreditBuffer = settingsManager.getPurchaseCreditBuffer()
    val paymentPreview = remember(paymentCartLines, customerBalance, paymentBarDiscount, purchaseCreditBuffer) {
        computePosPayment(paymentCartLines, customerBalance, paymentBarDiscount, purchaseCreditBuffer)
    }
    val totalAfterDiscount = paymentPreview.effectiveTotal

    val canValidate = (customerVolunteer != null || customerGuest != null) && cart.isNotEmpty() && !isProcessing
    val saleReady = (customerVolunteer != null || customerGuest != null) && cart.isNotEmpty()

    fun currentCustomerName(): String =
        customerVolunteer?.name ?: customerGuest?.name.orEmpty()

    fun isSameProfile(volunteer: Volunteer?, guest: Guest?): Boolean = when {
        volunteer != null -> customerVolunteer?.id == volunteer.id && customerGuest == null
        guest != null -> customerGuest?.nanoId == guest.nanoId && customerVolunteer == null
        else -> false
    }

    fun applyProfile(volunteer: Volunteer?, guest: Guest?) {
        customerVolunteer = volunteer
        customerGuest = guest
        customerOrgId = volunteer?.firebaseOrgId?.takeIf { it.isNotBlank() }
            ?: guest?.firebaseOrgId.orEmpty()
        scanFeedback = null
        scanFlashNonce++
    }

    fun handleProfileFound(volunteer: Volunteer?, guest: Guest?, displayName: String) {
        if (pendingProfileSwitch != null) return
        if (isSameProfile(volunteer, guest)) return

        val hasCustomer = customerVolunteer != null || customerGuest != null
        when {
            !hasCustomer -> applyProfile(volunteer, guest)
            cart.isEmpty() -> applyProfile(volunteer, guest)
            else -> pendingProfileSwitch = PosPendingProfileSwitch(volunteer, guest, displayName)
        }
    }

    fun clearCustomer() {
        customerVolunteer = null
        customerGuest = null
        customerOrgId = ""
        cart = emptyList()
        scanFeedback = null
        pendingProfileSwitch = null
    }

    fun resolveUid(uid: String) {
        viewModel.consumePosSaleUiResult()
        val normalized = uid.trim().replace(" ", "").replace(":", "").uppercase()
        val volMatches = MultiOrgMerge.findVolunteersByNfcUid(volunteers, normalized)
        if (volMatches.size == 1) {
            handleProfileFound(volMatches.first().value, null, volMatches.first().value.name)
            return
        }
        if (volMatches.size > 1) {
            handleProfileFound(volMatches.first().value, null, volMatches.first().value.name)
            return
        }
        val guestMatches = MultiOrgMerge.findGuestsByNfcUid(permanentGuests, normalized)
        if (guestMatches.size == 1) {
            handleProfileFound(null, guestMatches.first().value, guestMatches.first().value.name)
            return
        }
        if (guestMatches.size > 1) {
            handleProfileFound(null, guestMatches.first().value, guestMatches.first().value.name)
            return
        }
        if (pendingProfileSwitch == null) {
            scanFeedback = PosScanFeedback.Unknown
            feedbackNonce++
            scanFlashNonce++
        }
    }

    fun refuse(message: String) {
        scanFeedback = PosScanFeedback.Refused(message)
        feedbackNonce++
    }

    LaunchedEffect(feedbackNonce) {
        if (scanFeedback == null) return@LaunchedEffect
        delay(4_000)
        scanFeedback = null
    }

    fun executeSale() {
        if (isProcessing) return
        if (customerVolunteer == null && customerGuest == null) return
        if (cart.isEmpty()) return
        val vol = customerVolunteer
        val gst = customerGuest
        val cartSnapshot = paymentCartLines
        val discountSnapshot = paymentBarDiscount
        val venueSnapshot = selectedVenue
        // Keep the cash dialog open with frozen snapshot + loading until the sale
        // finishes — closing it early left a blank gap before the success overlay.
        viewModel.submitPosSale(
            holderType = if (vol != null) AccountHolderType.VOLUNTEER else AccountHolderType.GUEST,
            holderId = vol?.id ?: gst!!.nanoId,
            holderName = vol?.name ?: gst!!.name,
            cart = cartSnapshot,
            barDiscountPercent = discountSnapshot,
            posVenueName = venueSnapshot,
            customerOrgId = customerOrgId,
        )
    }

    LaunchedEffect(saleUiResult) {
        val result = saleUiResult ?: return@LaunchedEffect
        pendingCashConfirmation = false
        cashConfirmSnapshot = null
        if (result.success) {
            cart = emptyList()
        }
    }

    fun validateSale() {
        if (!canValidate) return
        val refusal = DepositReturnPolicy.firstRefusal(
            paymentCartLines,
            customerTransfers,
            System.currentTimeMillis(),
        )
        if (refusal != null) {
            scope.launch { refuse(depositRefusalMessage(refusal)) }
            return
        }
        if (paymentPreview.cashOrCardDue > 0) {
            cashConfirmSnapshot = paymentPreview
            cashConfirmBarDiscount = paymentBarDiscount
            pendingCashConfirmation = true
            return
        }
        executeSale()
    }

    fun resolveForCustomerOrg(item: SalesSheetItem): SalesSheetItem =
        if (isAllOrgsMode && customerOrgId.isNotBlank()) {
            mergedPosProducts
                .firstOrNull { it.displayItem.name == item.name && it.displayItem.price == item.price }
                ?.let { MultiOrgMerge.resolvePosProductForOrg(it, customerOrgId) }
                ?: item
        } else {
            item
        }

    /**
     * Deposit returns are synthetic tiles, so re-derive them from the customer's own org row and
     * refuse anything the account has not backed with a purchase in the last 24 hours.
     */
    fun addDepositReturnToCart(returnTile: SalesSheetItem) {
        val product = salesItems.find { it.id == PosDeposit.productIdForReturn(returnTile.id) } ?: return
        val resolved = PosDeposit.returnItemFor(resolveForCustomerOrg(product), returnTile.name)
        val productId = PosDeposit.productIdForReturn(resolved.id)
        val alreadyInCart = cart.filter { it.line.itemId == resolved.id }.sumOf { it.line.quantity }
        val returnable = DepositReturnPolicy
            .returnableCounts(customerTransfers, System.currentTimeMillis())[productId] ?: 0
        if (alreadyInCart >= returnable) {
            val refusal = DepositReturnPolicy.Refusal(
                productItemId = productId,
                returnName = resolved.name,
                requested = alreadyInCart + 1,
                allowed = returnable,
            )
            scope.launch { refuse(depositRefusalMessage(refusal)) }
            return
        }
        cart = addLineToCart(
            cart,
            PosCartLine(resolved.id, resolved.name, resolved.price, 1, resolved.emoji),
        )
    }

    fun addItemToCart(item: SalesSheetItem) {
        if (customerVolunteer == null && customerGuest == null) return
        if (PosDeposit.isReturnId(item.id)) {
            addDepositReturnToCart(item)
            return
        }
        val resolved = resolveForCustomerOrg(item)
        cart = addLineToCart(
            cart,
            PosCartLine(resolved.id, resolved.name, resolved.price, 1, resolved.emoji, barDiscountEligible = resolved.isBarDiscountEligible())
        )
    }

    fun addManualAmount(amount: Double) {
        if (customerVolunteer == null && customerGuest == null) return
        val manualEligible = paymentBarDiscount > 0
        cart = cart + PosCartEntry(
            PosCartLine(null, manualDefaultLabel, amount, 1, barDiscountEligible = manualEligible)
        )
        showManualAmount = false
    }

    NfcUidListenerEffect(
        platformContext = platformContext,
        enabled = !isProcessing,
        onUidRead = { resolveUid(it) },
        onScanStatus = { readerStatus = it },
    )

    LaunchedEffect(isDesktop, showResult) {
        if (isDesktop) focusRequester.requestFocus()
    }

    if (pendingCashConfirmation) {
        val snapshot = cashConfirmSnapshot ?: paymentPreview
        PosCashPaymentDialog(
            creditPaid = snapshot.creditPaid,
            cashDue = snapshot.cashOrCardDue,
            cashDueBeforeDiscount = snapshot.cashOrCardBeforeDiscount,
            barDiscountPercent = cashConfirmBarDiscount,
            currencyCode = currencyCode,
            isProcessing = isProcessing,
            onConfirm = { executeSale() },
            onCancel = {
                if (!isProcessing) {
                    pendingCashConfirmation = false
                    cashConfirmSnapshot = null
                }
            },
        )
    }

    if (showResult != null) {
        PosResultOverlay(
            state = showResult!!,
            currencyCode = currencyCode,
            isDesktop = isDesktop,
            focusRequester = focusRequester,
            onDismiss = {
                val refused = saleUiResult?.success != true
                viewModel.consumePosSaleUiResult()
                // A refused sale wrote nothing, so hand the till back its cart and customer.
                if (!refused) {
                    cart = emptyList()
                    customerVolunteer = null
                    customerGuest = null
                    scanFeedback = null
                    pendingProfileSwitch = null
                }
            }
        )
        return
    }

    val enterModifier = if (isDesktop) {
        Modifier
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && canValidate) {
                    validateSale()
                    true
                } else false
            }
    } else Modifier

    val nfcScanningActive = nfcAvailability == NfcInputAvailability.ExternalReader ||
        nfcAvailability == NfcInputAvailability.BuiltIn

    Box(Modifier.fillMaxSize().then(enterModifier)) {
        Column(Modifier.fillMaxSize().padding(padding)) {
            PosHeaderSection(
                viewModel = viewModel,
                onBack = onBack,
                onOpenSettings = onOpenSettings,
                customerVolunteer = customerVolunteer,
                customerGuest = customerGuest,
                customerOrgId = customerOrgId,
                balance = customerBalance,
                currencyCode = currencyCode,
                readerStatus = readerStatus,
                nfcAvailability = nfcAvailability,
                scanFeedback = scanFeedback,
                scanFlashNonce = scanFlashNonce,
                feedbackNonce = feedbackNonce,
                nfcScanningActive = nfcScanningActive,
                customerBarDiscount = paymentBarDiscount,
                onClearCustomer = { clearCustomer() },
                onOpenQr = { showQr = true },
            )

            Spacer(Modifier.height(8.dp))

            if (isCompact) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PosCategoryFilterRail(
                            selectedCategory = selectedCategory,
                            venues = venues,
                            selectedVenue = selectedVenue,
                            onVenueSelected = { venue ->
                                selectedVenue = venue
                                settingsManager.setPosSelectedVenue(venue)
                            },
                            isDesktop = isDesktop,
                            onSelect = { selectedCategory = it },
                            viewModel = viewModel,
                        )
                        PosItemsPane(
                            items = visibleItems,
                            subcategories = subcategoryOptions,
                            selectedSubcategory = selectedSubcategory,
                            onSelectSubcategory = { selectedSubcategory = it },
                            currencyCode = currencyCode,
                            hasCustomer = hasCustomer,
                            solidBackground = posAnimatedBackground,
                            onItemClick = { if (!isProcessing) addItemToCart(it) },
                            onManualClick = { if (!isProcessing) showManualAmount = true },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    PosCartBar(
                        cart, currencyCode, totalAfterDiscount, hasCustomer,
                        onRemove = { key -> if (!isProcessing) cart = cart.filterNot { it.key == key } },
                        onClear = { if (!isProcessing) cart = emptyList() },
                        onValidate = { validateSale() },
                        enabled = saleReady,
                        isProcessing = isProcessing,
                    )
                }
            } else {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PosCategoryFilterRail(
                        selectedCategory = selectedCategory,
                        venues = venues,
                        selectedVenue = selectedVenue,
                        onVenueSelected = { venue ->
                            selectedVenue = venue
                            settingsManager.setPosSelectedVenue(venue)
                        },
                        isDesktop = isDesktop,
                        onSelect = { selectedCategory = it },
                        viewModel = viewModel,
                    )
                    PosItemsPane(
                        items = visibleItems,
                        subcategories = subcategoryOptions,
                        selectedSubcategory = selectedSubcategory,
                        onSelectSubcategory = { selectedSubcategory = it },
                        currencyCode = currencyCode,
                        hasCustomer = hasCustomer,
                        solidBackground = posAnimatedBackground,
                        onItemClick = { if (!isProcessing) addItemToCart(it) },
                        onManualClick = { if (!isProcessing) showManualAmount = true },
                        modifier = Modifier.weight(2f),
                    )
                    PosCartPanel(
                        cart = cart,
                        currencyCode = currencyCode,
                        total = totalAfterDiscount,
                        barDiscount = paymentBarDiscount,
                        cartShowsBarDiscount = cartShowsBarDiscount,
                        hasCustomer = hasCustomer,
                        onRemove = { key -> if (!isProcessing) cart = cart.filterNot { it.key == key } },
                        onClear = { if (!isProcessing) cart = emptyList() },
                        onValidate = { validateSale() },
                        enabled = saleReady,
                        isProcessing = isProcessing,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (showManualAmount) {
            PosManualAmountPanel(
                isCompact = isCompact,
                currencyCode = currencyCode,
                onDismiss = { showManualAmount = false },
                onAdd = { amount -> addManualAmount(amount) }
            )
        }
    }

    if (showQr) {
        QRScannerDialog(
            platformContext = platformContext,
            onDismiss = { showQr = false },
            onMatchFound = { match ->
                showQr = false
                when (match) {
                    is ScannerMatch.VolunteerMatch ->
                        handleProfileFound(match.volunteer, null, match.volunteer.name)
                    is ScannerMatch.GuestMatch -> {
                        if (permanentGuests.any { it.nanoId == match.guest.nanoId }) {
                            handleProfileFound(null, match.guest, match.guest.name)
                        }
                    }
                }
            },
            volunteers = volunteers,
            guests = permanentGuests
        )
    }

    pendingProfileSwitch?.let { pending ->
        PosProfileSwitchDialog(
            newProfileName = pending.displayName,
            currentProfileName = currentCustomerName(),
            onSwitchKeepCart = {
                applyProfile(pending.volunteer, pending.guest)
                pendingProfileSwitch = null
            },
            onSwitchClearCart = {
                applyProfile(pending.volunteer, pending.guest)
                cart = emptyList()
                pendingProfileSwitch = null
            },
            onKeepCurrent = { pendingProfileSwitch = null },
        )
    }
}

@Composable
private fun PosManualAmountPanel(
    isCompact: Boolean,
    currencyCode: String,
    onDismiss: () -> Unit,
    onAdd: (Double) -> Unit
) {
    var amountText by remember { mutableStateOf("") }

    fun tryAdd() {
        val amount = amountText.toDoubleOrNull() ?: return
        if (amount > 0) onAdd(amount)
    }

    if (isCompact) {
        ModalBottomSheet(onDismissRequest = onDismiss) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    stringResource(Res.string.pos_manual_amount),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                CurrencyNumpad(
                    amountText = amountText,
                    onDigit = { amountText = appendNumpadDigit(amountText, it) },
                    onBackspace = { amountText = backspaceNumpad(amountText) },
                    onClear = { amountText = "" }
                )
                Button(onClick = { tryAdd() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.numpad_add_to_cart))
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    } else {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.CenterEnd
        ) {
            Card(
                Modifier
                    .width(360.dp)
                    .fillMaxHeight(0.92f)
                    .padding(16.dp)
                    .clickable(enabled = false, onClick = {})
            ) {
                Column(
                    Modifier.padding(20.dp).fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(Res.string.pos_manual_amount),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.close))
                        }
                    }
                    CurrencyNumpad(
                        amountText = amountText,
                        onDigit = { amountText = appendNumpadDigit(amountText, it) },
                        onBackspace = { amountText = backspaceNumpad(amountText) },
                        onClear = { amountText = "" },
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = { tryAdd() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text(stringResource(Res.string.numpad_add_to_cart))
                    }
                }
            }
        }
    }
}

private fun String.normalizeNfc() = NfcUid.normalize(this)

private sealed class PosResultState {
    data class Success(val result: com.eventmanager.app.data.utils.PosSaleResult) : PosResultState()
    /** Sale rejected before anything was written — the cart is kept so it can be corrected. */
    data class Failure(val message: String) : PosResultState()
}

@Composable
private fun PosResultOverlay(
    state: PosResultState,
    currencyCode: String,
    isDesktop: Boolean,
    focusRequester: FocusRequester,
    onDismiss: () -> Unit
) {
    val result = (state as? PosResultState.Success)?.result
    val accent = if (result != null) Color(0xFF43A047) else MaterialTheme.colorScheme.error

    val keyModifier = if (isDesktop) {
        Modifier
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                    onDismiss()
                    true
                } else false
            }
    } else Modifier

    LaunchedEffect(Unit) {
        if (isDesktop) focusRequester.requestFocus()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
            .then(keyModifier),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth(0.92f)
                .widthIn(min = 340.dp, max = 440.dp),
            shape = PosCardShape,
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                Modifier.padding(horizontal = 28.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = accent.copy(alpha = 0.12f),
                    modifier = Modifier.size(72.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (result != null) Icons.Default.Check else Icons.Default.Block,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = accent
                        )
                    }
                }
                Text(
                    if (result != null) {
                        stringResource(Res.string.pos_sale_success)
                    } else {
                        stringResource(Res.string.pos_sale_refused)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                if (state is PosResultState.Failure) {
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                // Cash was already confirmed in PosCashPaymentDialog — don't re-prompt "Pay …".
                // Only show the account's credit after a successful debit or deposit refund.
                if (result != null && result.creditPaid != 0.0) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(Res.string.pos_remaining_credit_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            formatMoney(result.remainingBalance, currencyCode),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (result.remainingBalance < 0) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            textAlign = TextAlign.Center,
                        )
                        if (result.wentNegativeViaBuffer) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(
                                    Res.string.pos_negative_balance_message,
                                    formatMoney(result.remainingBalance, currencyCode),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                } else if (result != null && result.cashOrCardDue > 0) {
                    Text(
                        stringResource(
                            Res.string.pos_report_preview_cash,
                            formatMoney(result.cashOrCardDue, currencyCode),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = PosTileShape,
                ) {
                    if (result != null) {
                        Icon(
                            Icons.Default.Nfc,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        if (result != null) {
                            stringResource(Res.string.pos_sale_next)
                        } else {
                            stringResource(Res.string.pos_sale_refused_back_to_cart)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (result != null) {
                    Text(
                        if (isDesktop) {
                            stringResource(Res.string.pos_sale_next_shortcut)
                        } else {
                            stringResource(Res.string.pos_sale_next_hint)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun PosCashPaymentDialog(
    creditPaid: Double,
    cashDue: Double,
    cashDueBeforeDiscount: Double,
    barDiscountPercent: Int,
    currencyCode: String,
    isProcessing: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val barDiscountApplied = barDiscountPercent > 0 && cashDueBeforeDiscount > cashDue + 0.001
    Dialog(
        onDismissRequest = { if (!isProcessing) onCancel() },
        properties = phoneFractionDialogProperties(),
    ) {
        DialogFractionSizer(profile = FractionalDialogProfile.Card) { maxDialogWidth, maxDialogHeight ->
            Card(
                modifier = Modifier
                    .widthIn(max = maxDialogWidth.coerceAtMost(440.dp))
                    .heightIn(max = maxDialogHeight)
                    .padding(16.dp),
                shape = PosCardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
            Column(
                Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(Res.string.pos_cash_payment_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(
                        Res.string.pos_cash_payment_credit_covers,
                        formatMoney(creditPaid, currencyCode),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(Res.string.pos_cash_payment_due_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                if (barDiscountApplied) {
                    Text(
                        formatMoney(cashDueBeforeDiscount, currencyCode),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
                Text(
                    formatMoney(cashDue, currencyCode),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                if (barDiscountApplied) {
                    Text(
                        stringResource(Res.string.pos_cash_payment_bar_discount_applied, barDiscountPercent),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Button(
                    onClick = { if (!isProcessing) onConfirm() },
                    enabled = true,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = PosTileShape,
                ) {
                    PosValidateButtonContent(isProcessing = isProcessing, cashConfirm = true)
                }
                OutlinedButton(
                    onClick = onCancel,
                    enabled = !isProcessing,
                    modifier = Modifier.fillMaxWidth(),
                    shape = PosTileShape,
                ) {
                    Text(stringResource(Res.string.pos_cash_payment_cancel))
                }
            }
            }
        }
    }
}

@Composable
private fun PosProfileSwitchDialog(
    newProfileName: String,
    currentProfileName: String,
    onSwitchKeepCart: () -> Unit,
    onSwitchClearCart: () -> Unit,
    onKeepCurrent: () -> Unit,
) {
    Dialog(
        onDismissRequest = onKeepCurrent,
        properties = phoneFractionDialogProperties(),
    ) {
        DialogFractionSizer(profile = FractionalDialogProfile.Card) { maxDialogWidth, maxDialogHeight ->
            Card(
                modifier = Modifier
                    .widthIn(max = maxDialogWidth.coerceAtMost(440.dp))
                    .heightIn(max = maxDialogHeight)
                    .padding(16.dp),
                shape = PosCardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
            Column(
                Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    stringResource(Res.string.pos_profile_switch_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(Res.string.pos_profile_switch_message, newProfileName, currentProfileName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onSwitchKeepCart,
                    modifier = Modifier.fillMaxWidth(),
                    shape = PosTileShape
                ) {
                    Text(stringResource(Res.string.pos_profile_switch_keep_cart))
                }
                OutlinedButton(
                    onClick = onSwitchClearCart,
                    modifier = Modifier.fillMaxWidth(),
                    shape = PosTileShape
                ) {
                    Text(stringResource(Res.string.pos_profile_switch_clear_cart))
                }
                TextButton(
                    onClick = onKeepCurrent,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(Res.string.pos_profile_switch_keep_current))
                }
            }
        }
        }
    }
}

@Composable
private fun PosHeaderSection(
    viewModel: EventManagerViewModel,
    onBack: () -> Unit,
    onOpenSettings: (() -> Unit)?,
    customerVolunteer: Volunteer?,
    customerGuest: Guest?,
    customerOrgId: String,
    balance: Double,
    currencyCode: String,
    readerStatus: String?,
    nfcAvailability: NfcInputAvailability,
    scanFeedback: PosScanFeedback?,
    scanFlashNonce: Int,
    feedbackNonce: Int,
    nfcScanningActive: Boolean,
    customerBarDiscount: Int,
    onClearCustomer: () -> Unit,
    onOpenQr: () -> Unit,
) {
    val hasCustomer = customerVolunteer != null || customerGuest != null
    val isErrorScan = scanFeedback != null
    val nfcReady = nfcAvailability == NfcInputAvailability.ExternalReader ||
        nfcAvailability == NfcInputAvailability.BuiltIn

    val infiniteTransition = rememberInfiniteTransition(label = "nfcPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "nfcScale"
    )

    val burstProgress = remember { Animatable(0f) }
    val avatarScale = remember { Animatable(1f) }
    val borderPulse = remember { Animatable(0f) }

    LaunchedEffect(scanFlashNonce) {
        if (scanFlashNonce == 0) return@LaunchedEffect
        burstProgress.snapTo(0f)
        borderPulse.snapTo(1f)
        avatarScale.snapTo(0.82f)
        coroutineScope {
            launch {
                burstProgress.animateTo(1f, tween(750, easing = FastOutSlowInEasing))
            }
            launch {
                avatarScale.animateTo(1.1f, tween(140, easing = FastOutSlowInEasing))
                avatarScale.animateTo(
                    1f,
                    spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
                )
            }
            launch {
                borderPulse.animateTo(0f, tween(1_000, easing = FastOutSlowInEasing))
            }
        }
    }

    val scanAccent = if (isErrorScan) Color(0xFFE53935) else Color(0xFF43A047)

    val scannerSectionColor = when {
        isErrorScan -> MaterialTheme.colorScheme.errorContainer
        hasCustomer -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }

    Box(Modifier.fillMaxWidth()) {
        if (burstProgress.value in 0.01f..0.99f) {
            PosScanRippleOverlay(
                progress = burstProgress.value,
                color = scanAccent,
                modifier = Modifier.matchParentSize()
            )
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val pulse = borderPulse.value
                    if (pulse > 0.01f) {
                        drawRoundRect(
                            color = scanAccent.copy(alpha = pulse * 0.85f),
                            cornerRadius = CornerRadius(16.dp.toPx()),
                            style = Stroke(width = 3.dp.toPx() * pulse)
                        )
                    }
                },
            shape = PosCardShape,
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Column(Modifier.fillMaxWidth()) {
                PosTopBar(
                    viewModel = viewModel,
                    onBack = onBack,
                    onOpenSettings = onOpenSettings,
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                )

                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(scannerSectionColor)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AnimatedContent(
                        targetState = hasCustomer,
                        transitionSpec = {
                            (fadeIn(tween(220)) + scaleIn(initialScale = 0.94f, animationSpec = tween(280)) +
                                slideInVertically { fullHeight -> fullHeight / 5 })
                                .togetherWith(
                                    fadeOut(tween(180)) + scaleOut(targetScale = 0.97f, animationSpec = tween(200)) +
                                        slideOutVertically { fullHeight -> -fullHeight / 6 }
                                )
                        },
                        label = "posCustomerState"
                    ) { customerSelected ->
                        if (customerSelected) {
                            PosCustomerProfileContent(
                                viewModel = viewModel,
                                customerVolunteer = customerVolunteer,
                                customerGuest = customerGuest,
                                customerOrgId = customerOrgId,
                                balance = balance,
                                currencyCode = currencyCode,
                                customerBarDiscount = customerBarDiscount,
                                avatarScale = avatarScale.value,
                                nfcScanningActive = nfcScanningActive,
                                nfcPulseScale = pulseScale,
                                onClearCustomer = onClearCustomer,
                            )
                        } else {
                            PosCustomerWaitingContent(
                                readerStatus = readerStatus,
                                nfcAvailability = nfcAvailability,
                                pulseScale = if (nfcReady) pulseScale else 1f,
                                onOpenQr = onOpenQr,
                            )
                        }
                    }

                    if (scanFeedback != null) {
                        PosScanFeedbackRow(
                            message = when (scanFeedback) {
                                PosScanFeedback.Unknown -> stringResource(Res.string.pos_unknown_card)
                                is PosScanFeedback.Refused -> scanFeedback.message
                            },
                            nonce = feedbackNonce,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PosTopBar(
    viewModel: EventManagerViewModel,
    onBack: () -> Unit,
    onOpenSettings: (() -> Unit)?,
) {
    val isDesktop = LocalPlatformContext.current.isDesktop

    if (isDesktop) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.Default.ArrowBack, contentDescription = null)
            }
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.PointOfSale,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    stringResource(Res.string.pos_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SyncStatusPill(viewModel = viewModel)
                if (onOpenSettings != null) {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(Res.string.settings_title))
                    }
                } else {
                    Spacer(Modifier.width(48.dp))
                }
            }
        }
        return
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = null)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.PointOfSale,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Text(
                stringResource(Res.string.pos_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SyncStatusPill(viewModel = viewModel)
            if (onOpenSettings != null) {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = stringResource(Res.string.settings_title))
                }
            } else {
                Spacer(Modifier.width(48.dp))
            }
        }
    }
}

private fun categoryIcon(category: SalesCategory): ImageVector = when (category) {
    SalesCategory.MERCH -> Icons.Default.Storefront
    SalesCategory.ENTRY -> Icons.Default.ConfirmationNumber
    SalesCategory.BAR -> Icons.Default.LocalBar
    SalesCategory.OTHER -> Icons.Default.Category
}

@Composable
private fun PosBarDiscountBenefitBadge(
    discountPercent: Int,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(Res.string.pos_bar_discount_badge, discountPercent)
    val hint = stringResource(Res.string.pos_bar_discount_cash_hint)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.72f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f),
        ),
        modifier = modifier.semantics { contentDescription = "$label — $hint" },
    ) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Default.LocalBar,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "-$discountPercent%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                stringResource(Res.string.pos_bar_discount_at_bar),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PosCustomerNameAndChip(
    viewModel: EventManagerViewModel,
    customerVolunteer: Volunteer?,
    customerGuest: Guest?,
    customerOrgId: String,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (customerOrgId.isNotBlank() && viewModel.isFirebaseAllOrgsMode()) {
                OrgColorDot(orgId = customerOrgId, viewModel = viewModel, size = 12.dp)
            }
            Text(
                customerVolunteer?.name ?: customerGuest?.name.orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        AssistChip(
            onClick = {},
            label = {
                Text(
                    if (customerVolunteer != null) {
                        stringResource(Res.string.volunteer_label)
                    } else {
                        stringResource(Res.string.pos_customer_guest_label)
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            },
            leadingIcon = {
                Icon(
                    if (customerVolunteer != null) Icons.Default.Star else Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
        )
    }
}

@Composable
private fun PosCustomerBalanceDisplay(
    balance: Double,
    currencyCode: String,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
) {
    Column(horizontalAlignment = horizontalAlignment) {
        Text(
            stringResource(Res.string.account_amount_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
        )
        Text(
            formatMoney(balance, currencyCode),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = if (balance < 0) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
    }
}

@Composable
private fun PosCustomerProfileActions(
    nfcScanningActive: Boolean,
    nfcPulseScale: Float,
    onClearCustomer: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (nfcScanningActive) {
            Icon(
                Icons.Default.Nfc,
                contentDescription = stringResource(Res.string.pos_reader_scanning),
                modifier = Modifier
                    .size(22.dp)
                    .scale(nfcPulseScale),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(onClick = onClearCustomer) {
            Icon(Icons.Default.SwapHoriz, contentDescription = stringResource(Res.string.pos_clear_customer))
        }
    }
}

@Composable
private fun PosCustomerAvatar(avatarScale: Float) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        modifier = Modifier
            .size(52.dp)
            .scale(avatarScale),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.Person,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun PosCustomerProfileContent(
    viewModel: EventManagerViewModel,
    customerVolunteer: Volunteer?,
    customerGuest: Guest?,
    customerOrgId: String,
    balance: Double,
    currencyCode: String,
    customerBarDiscount: Int,
    avatarScale: Float,
    nfcScanningActive: Boolean,
    nfcPulseScale: Float,
    onClearCustomer: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wideProfile = maxWidth >= 520.dp

        if (wideProfile) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PosCustomerAvatar(avatarScale)
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    PosCustomerNameAndChip(
                        viewModel = viewModel,
                        customerVolunteer = customerVolunteer,
                        customerGuest = customerGuest,
                        customerOrgId = customerOrgId,
                    )
                    PosCustomerBalanceDisplay(
                        balance = balance,
                        currencyCode = currencyCode,
                    )
                    if (customerBarDiscount > 0) {
                        PosBarDiscountBenefitBadge(discountPercent = customerBarDiscount)
                    }
                }
                PosCustomerProfileActions(
                    nfcScanningActive = nfcScanningActive,
                    nfcPulseScale = nfcPulseScale,
                    onClearCustomer = onClearCustomer,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        PosCustomerAvatar(avatarScale)
                        PosCustomerNameAndChip(
                            viewModel = viewModel,
                            customerVolunteer = customerVolunteer,
                            customerGuest = customerGuest,
                            customerOrgId = customerOrgId,
                        )
                    }
                    PosCustomerProfileActions(
                        nfcScanningActive = nfcScanningActive,
                        nfcPulseScale = nfcPulseScale,
                        onClearCustomer = onClearCustomer,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PosCustomerBalanceDisplay(
                        balance = balance,
                        currencyCode = currencyCode,
                    )
                    if (customerBarDiscount > 0) {
                        PosBarDiscountBenefitBadge(discountPercent = customerBarDiscount)
                    }
                }
            }
        }
    }
}

@Composable
private fun PosCustomerWaitingContent(
    readerStatus: String?,
    nfcAvailability: NfcInputAvailability,
    pulseScale: Float,
    onOpenQr: () -> Unit,
) {
    val nfcReady = nfcAvailability == NfcInputAvailability.ExternalReader ||
        nfcAvailability == NfcInputAvailability.BuiltIn
    val iconTint = when (nfcAvailability) {
        NfcInputAvailability.BuiltInDisabled,
        NfcInputAvailability.Unavailable -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val statusText = when (nfcAvailability) {
        NfcInputAvailability.ExternalReader -> stringResource(Res.string.pos_reader_waiting)
        NfcInputAvailability.BuiltIn -> stringResource(Res.string.place_nfc_card_on_phone)
        NfcInputAvailability.BuiltInDisabled -> stringResource(Res.string.nfc_disabled_enable)
        NfcInputAvailability.Unavailable -> readerStatus
            ?: stringResource(Res.string.usb_reader_not_connected)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            Icons.Default.Nfc,
            contentDescription = null,
            modifier = Modifier
                .size(32.dp)
                .scale(pulseScale),
            tint = iconTint
        )
        Column(Modifier.weight(1f)) {
            Text(
                when (nfcAvailability) {
                    NfcInputAvailability.Unavailable -> stringResource(Res.string.pos_reader_not_connected)
                    else -> stringResource(Res.string.pos_waiting_card)
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (!readerStatus.isNullOrBlank() && nfcAvailability == NfcInputAvailability.Unavailable) {
                    readerStatus!!
                } else {
                    statusText
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (nfcReady) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }
        OutlinedButton(
            onClick = onOpenQr,
            shape = PosTileShape,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(Res.string.pos_use_qr))
        }
    }
}

@Composable
private fun PosScanFeedbackRow(message: String, nonce: Int) {
    val color = Color(0xFFE53935)
    val icon = Icons.Default.Warning

    val slideOffset = remember { Animatable(-12f) }
    val rowAlpha = remember { Animatable(0f) }

    LaunchedEffect(nonce) {
        slideOffset.snapTo(-12f)
        rowAlpha.snapTo(0f)
        coroutineScope {
            launch {
                slideOffset.animateTo(0f, tween(280, easing = FastOutSlowInEasing))
            }
            launch {
                rowAlpha.animateTo(1f, tween(220))
            }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .offset(y = slideOffset.value.dp)
            .alpha(rowAlpha.value),
        shape = PosTileShape,
        color = color.copy(alpha = 0.12f)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = color, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PosScanRippleOverlay(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxRadius = size.maxDimension * 0.72f
        listOf(0f, 0.18f).forEach { delay ->
            val normalized = ((progress - delay) / (1f - delay)).coerceIn(0f, 1f)
            if (normalized > 0f) {
                drawCircle(
                    color = color.copy(alpha = (1f - normalized) * 0.28f),
                    radius = maxRadius * normalized,
                    center = center,
                    style = Stroke(width = 2.5.dp.toPx() * (1f - normalized * 0.5f))
                )
            }
        }
    }
}

@Composable
private fun posCategoryLabel(category: SalesCategory?): String = when (category) {
    null -> stringResource(Res.string.pos_category_all)
    SalesCategory.MERCH -> stringResource(Res.string.sales_category_merch)
    SalesCategory.ENTRY -> stringResource(Res.string.sales_category_entry)
    SalesCategory.BAR -> stringResource(Res.string.sales_category_bar)
    SalesCategory.OTHER -> stringResource(Res.string.sales_category_other)
}

@Composable
private fun PosCategoryFilterRail(
    selectedCategory: SalesCategory?,
    venues: List<VenueEntity>,
    selectedVenue: String,
    onVenueSelected: (String) -> Unit,
    isDesktop: Boolean,
    onSelect: (SalesCategory?) -> Unit,
    viewModel: EventManagerViewModel? = null,
    modifier: Modifier = Modifier,
) {
    val options = listOf(
        null to Icons.Default.Apps,
    ) + SalesCategory.entries.map { it to categoryIcon(it) }

    Surface(
        modifier = modifier.width(48.dp).fillMaxHeight(),
        shape = PosTileShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .padding(vertical = 6.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                options.forEach { (category, icon) ->
                    PosCategoryFilterItem(
                        category = category,
                        icon = icon,
                        selected = selectedCategory == category,
                        isDesktop = isDesktop,
                        onSelect = { onSelect(category) },
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            HorizontalDivider(
                modifier = Modifier
                    .width(28.dp)
                    .padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
            if (viewModel != null) {
                FirebaseOrgSwitcher(
                    viewModel = viewModel,
                    placement = FirebaseOrgSwitcherPlacement.PosVenueStyle,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                HorizontalDivider(
                    modifier = Modifier
                        .width(28.dp)
                        .padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
            }
            PosVenueFilterItem(
                venues = venues,
                selectedVenue = selectedVenue,
                onVenueSelected = onVenueSelected,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PosVenueFilterItem(
    venues: List<VenueEntity>,
    selectedVenue: String,
    onVenueSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeVenues = remember(venues) { venues.filter { it.isActive } }
    var menuExpanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val venueLabel = if (selectedVenue == PosVenueScope.GLOBAL) {
        stringResource(Res.string.pos_venue_global)
    } else {
        selectedVenue
    }
    val isSpecificVenue = selectedVenue != PosVenueScope.GLOBAL
    val labelColor = if (isSpecificVenue) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val openMenu = {
        menuExpanded = true
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .hoverable(interactionSource),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val buttonColor = when {
            menuExpanded -> MaterialTheme.colorScheme.secondaryContainer
            isSpecificVenue -> MaterialTheme.colorScheme.tertiaryContainer
            isHovered -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        }
        val iconTint = when {
            menuExpanded -> MaterialTheme.colorScheme.onSecondaryContainer
            isSpecificVenue -> MaterialTheme.colorScheme.onTertiaryContainer
            isHovered -> MaterialTheme.colorScheme.onSecondaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        val venueLabelStyle = MaterialTheme.typography.labelSmall.copy(
            fontSize = MaterialTheme.typography.labelSmall.fontSize * 1.15f,
            lineHeight = MaterialTheme.typography.labelSmall.fontSize * 1.15f,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = openMenu,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            posVerticalVenueLabel(venueLabel).forEach { char ->
                if (char == '\u00AD') {
                    Spacer(Modifier.height(3.dp))
                } else {
                    Text(
                        text = char.toString(),
                        style = venueLabelStyle,
                        fontWeight = if (isSpecificVenue) FontWeight.SemiBold else FontWeight.Medium,
                        color = labelColor,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Surface(
                onClick = openMenu,
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = buttonColor,
                interactionSource = interactionSource,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Place,
                        contentDescription = stringResource(Res.string.pos_venue_selector_label),
                        tint = iconTint,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.pos_venue_global)) },
                    leadingIcon = if (selectedVenue == PosVenueScope.GLOBAL) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else null,
                    onClick = {
                        onVenueSelected(PosVenueScope.GLOBAL)
                        menuExpanded = false
                    },
                )
                activeVenues.forEach { venue ->
                    val selected = venue.name.equals(selectedVenue, ignoreCase = true)
                    DropdownMenuItem(
                        text = { Text(venue.name, maxLines = 2) },
                        leadingIcon = if (selected) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else null,
                        onClick = {
                            onVenueSelected(venue.name)
                            menuExpanded = false
                        },
                    )
                }
            }
        }
    }
}

/** One character per line; word breaks become small gaps. Truncates long venue names. */
private fun posVerticalVenueLabel(raw: String, maxChars: Int = 16): List<Char> {
    val normalized = raw.trim().ifEmpty { PosVenueScope.GLOBAL }
    val truncated = if (normalized.length > maxChars) {
        normalized.take(maxChars - 1) + "…"
    } else {
        normalized
    }
    return buildList {
        truncated.forEach { char ->
            if (char == ' ') {
                add('\u00AD') // spacer marker between words
            } else {
                add(char)
            }
        }
    }
}

@Composable
private fun PosCategoryFilterItem(
    category: SalesCategory?,
    icon: ImageVector,
    selected: Boolean,
    isDesktop: Boolean,
    onSelect: () -> Unit,
) {
    val label = posCategoryLabel(category)
    var showTouchLabel by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val showHoverLabel = isDesktop && isHovered
    val showLabelPopup = showHoverLabel || showTouchLabel
    val density = LocalDensity.current
    val popupOffsetX = with(density) { 44.dp.roundToPx() }

    LaunchedEffect(showTouchLabel) {
        if (!showTouchLabel) return@LaunchedEffect
        delay(1_400)
        showTouchLabel = false
    }

    fun revealLabel() {
        showTouchLabel = true
    }

    Box(
        modifier = Modifier.size(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        val buttonColor = when {
            selected -> MaterialTheme.colorScheme.primaryContainer
            showHoverLabel -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        }
        val iconTint = when {
            selected -> MaterialTheme.colorScheme.primary
            showHoverLabel -> MaterialTheme.colorScheme.onSecondaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = buttonColor,
            modifier = Modifier
                .fillMaxSize()
                .scale(if (showHoverLabel) 1.06f else 1f)
                .then(
                    if (isDesktop) {
                        Modifier
                            .hoverable(interactionSource)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = {
                                    onSelect()
                                    revealLabel()
                                },
                            )
                    } else {
                        Modifier.pointerInput(category) {
                            detectTapGestures(
                                onTap = {
                                    onSelect()
                                    revealLabel()
                                },
                                onLongPress = { revealLabel() },
                            )
                        }
                    }
                ),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = label,
                    modifier = Modifier.size(22.dp),
                    tint = iconTint,
                )
            }
        }

        if (showLabelPopup) {
            Popup(
                alignment = Alignment.CenterStart,
                offset = IntOffset(popupOffsetX, 0),
                properties = PopupProperties(focusable = false),
            ) {
                AnimatedVisibility(
                    visible = showLabelPopup,
                    enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.9f, animationSpec = tween(180)),
                    exit = fadeOut(tween(200)) + scaleOut(targetScale = 0.92f, animationSpec = tween(180)),
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.inverseSurface,
                        shadowElevation = 4.dp,
                    ) {
                        Text(
                            label,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Product grid, with the sub-category filter bar stacked above it when the active general
 * category has any. Sits to the right of the vertical category rail so both filter levels
 * read as one control surface.
 */
@Composable
private fun PosItemsPane(
    items: List<SalesSheetItem>,
    subcategories: List<PosSubcategory>,
    selectedSubcategory: String?,
    onSelectSubcategory: (String?) -> Unit,
    currencyCode: String,
    hasCustomer: Boolean,
    solidBackground: Boolean,
    onItemClick: (SalesSheetItem) -> Unit,
    onManualClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (subcategories.isNotEmpty()) {
            PosSubcategoryFilterBar(
                subcategories = subcategories,
                selected = selectedSubcategory,
                onSelect = onSelectSubcategory,
            )
        }
        PosItemsGrid(
            items = items,
            currencyCode = currencyCode,
            hasCustomer = hasCustomer,
            solidBackground = solidBackground,
            onItemClick = onItemClick,
            onManualClick = onManualClick,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PosSubcategoryFilterBar(
    subcategories: List<PosSubcategory>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = PosTileShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PosSubcategoryChip(
                label = stringResource(Res.string.pos_subcategory_all),
                selected = selected == null,
                onClick = { onSelect(null) },
            )
            subcategories.forEach { subcategory ->
                PosSubcategoryChip(
                    label = subcategory.name,
                    selected = selected.equals(subcategory.name, ignoreCase = true),
                    onClick = { onSelect(subcategory.name) },
                )
            }
        }
    }
}

@Composable
private fun PosSubcategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background by animateColorAsState(
        if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f)
        },
        animationSpec = tween(180),
        label = "posSubcategoryChipBg",
    )
    val contentColor by animateColorAsState(
        if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(180),
        label = "posSubcategoryChipFg",
    )
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = background,
        contentColor = contentColor,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
            },
        ),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun PosItemsGrid(
    items: List<SalesSheetItem>,
    currencyCode: String,
    hasCustomer: Boolean,
    solidBackground: Boolean,
    onItemClick: (SalesSheetItem) -> Unit,
    onManualClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val width = if (maxWidth.value.isFinite()) maxWidth else 400.dp
        val height = if (maxHeight.value.isFinite()) maxHeight else 600.dp
        val cellCount = items.size + 1
        val spec = remember(width, height, cellCount) {
            resolvePosItemGridSpec(width, height, cellCount)
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(spec.columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(PosGridContentPadding),
            horizontalArrangement = Arrangement.spacedBy(PosGridGap),
            verticalArrangement = Arrangement.spacedBy(PosGridGap),
            userScrollEnabled = spec.scrollEnabled,
        ) {
            items(items, key = { it.id }) { item ->
                PosItemTile(
                    item = item,
                    currencyCode = currencyCode,
                    tileHeight = spec.tileHeight,
                    large = spec.largeTiles,
                    enabled = hasCustomer,
                    solidBackground = solidBackground,
                    onClick = { onItemClick(item) },
                )
            }
            item {
                PosManualTile(
                    tileHeight = spec.tileHeight,
                    large = spec.largeTiles,
                    enabled = hasCustomer,
                    solidBackground = solidBackground,
                    onClick = onManualClick,
                )
            }
        }
    }
}

@Composable
private fun PosItemTile(
    item: SalesSheetItem,
    currencyCode: String,
    tileHeight: Dp,
    large: Boolean,
    enabled: Boolean,
    solidBackground: Boolean,
    onClick: () -> Unit
) {
    val tileColor = if (solidBackground) {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val emojiCircleColor = if (solidBackground) {
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.98f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    Card(
        Modifier
            .fillMaxWidth()
            .height(tileHeight)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick),
        shape = PosTileShape,
        elevation = CardDefaults.cardElevation(
            defaultElevation = when {
                !enabled -> 0.dp
                solidBackground -> 4.dp
                else -> 1.dp
            }
        ),
        colors = CardDefaults.cardColors(
            containerColor = tileColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = if (solidBackground) 0.38f else 0.28f),
        ),
    ) {
        if (large) {
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = emojiCircleColor,
                        modifier = Modifier.size(64.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                item.emoji.ifBlank { "🛒" },
                                style = MaterialTheme.typography.headlineLarge
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        item.name,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                PosItemPriceBadge(
                    price = item.price,
                    currencyCode = currencyCode,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .zIndex(1f),
                )
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    PosItemPriceBadge(
                        price = item.price,
                        currencyCode = currencyCode,
                    )
                }
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = emojiCircleColor,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                item.emoji.ifBlank { "🛒" },
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }
                Text(
                    item.name,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun PosItemPriceBadge(
    price: Double,
    currencyCode: String,
    modifier: Modifier = Modifier,
) {
    // Deposit returns pay out instead of charging, so they get their own colour.
    val isRefund = price < 0.0
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = if (isRefund) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        shadowElevation = 2.dp,
    ) {
        Text(
            formatMoney(price, currencyCode),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (isRefund) {
                MaterialTheme.colorScheme.onTertiaryContainer
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            },
            maxLines = 1,
        )
    }
}

@Composable
private fun PosManualTile(
    tileHeight: Dp,
    large: Boolean,
    enabled: Boolean,
    solidBackground: Boolean,
    onClick: () -> Unit,
) {
    val tileAlpha = if (solidBackground) 0.94f else 0.6f
    Card(
        Modifier
            .fillMaxWidth()
            .height(tileHeight)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick),
        shape = PosTileShape,
        elevation = CardDefaults.cardElevation(
            defaultElevation = when {
                !enabled -> 0.dp
                solidBackground -> 4.dp
                else -> 1.dp
            }
        ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = tileAlpha),
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.tertiary.copy(alpha = if (solidBackground) 0.45f else 0.3f)
        )
    ) {
        Column(
            Modifier.padding(if (large) 16.dp else 12.dp).fillMaxWidth().fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f),
                modifier = Modifier.size(if (large) 56.dp else 40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(if (large) 28.dp else 22.dp),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(Res.string.pos_manual_amount),
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium,
                style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@Composable
private fun PosValidateButtonContent(
    isProcessing: Boolean,
    cashConfirm: Boolean = false,
) {
    if (isProcessing) {
        CircularProgressIndicator(
            modifier = Modifier.size(if (cashConfirm) 20.dp else 22.dp),
            color = MaterialTheme.colorScheme.onPrimary,
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(if (cashConfirm) 8.dp else 8.dp))
        Text(
            stringResource(Res.string.pos_sale_processing),
            style = if (cashConfirm) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
        )
    } else if (cashConfirm) {
        Text(stringResource(Res.string.pos_cash_payment_confirm))
    } else {
        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(Res.string.pos_validate_sale), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun PosCartPanel(
    cart: List<PosCartEntry>,
    currencyCode: String,
    total: Double,
    barDiscount: Int,
    cartShowsBarDiscount: Boolean,
    hasCustomer: Boolean,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    onValidate: () -> Unit,
    enabled: Boolean,
    isProcessing: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier,
        shape = PosCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(Modifier.padding(14.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.ShoppingCart, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Column {
                        Text(stringResource(Res.string.pos_cart_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        if (cart.isNotEmpty()) {
                            Text(
                                stringResource(Res.string.pos_items_count, cart.sumOf { it.line.quantity }),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                TextButton(onClick = onClear, enabled = cart.isNotEmpty() && !isProcessing) {
                    Text(stringResource(Res.string.pos_clear_cart))
                }
            }
            if (cart.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            Icons.Default.ShoppingCartCheckout,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Text(
                            if (hasCustomer) stringResource(Res.string.pos_cart_empty)
                            else stringResource(Res.string.pos_select_customer_first),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(cart, key = { it.key }) { entry ->
                        Surface(
                            shape = PosTileShape,
                            color = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                "${entry.line.quantity}",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    Text(entry.line.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                }
                                Text(
                                    formatMoney(entry.line.unitPrice * entry.line.quantity, currencyCode),
                                    fontWeight = FontWeight.Medium,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                IconButton(
                                    onClick = { onRemove(entry.key) },
                                    enabled = !isProcessing,
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (cartShowsBarDiscount && cart.isNotEmpty()) {
                Text(
                    stringResource(Res.string.bar_discount, barDiscount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.pos_total, ""), style = MaterialTheme.typography.titleMedium)
                Text(formatMoney(total, currencyCode), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            }
            Button(
                onClick = { if (!isProcessing) onValidate() },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = PosTileShape
            ) {
                PosValidateButtonContent(isProcessing = isProcessing)
            }
        }
    }
}

@Composable
private fun PosCartBar(
    cart: List<PosCartEntry>,
    currencyCode: String,
    total: Double,
    hasCustomer: Boolean,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    onValidate: () -> Unit,
    enabled: Boolean,
    isProcessing: Boolean,
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = PosCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.ShoppingCart, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Text(
                        stringResource(Res.string.pos_total, formatMoney(total, currencyCode)),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                if (cart.isNotEmpty()) {
                    Surface(shape = PosTileShape, color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(
                            stringResource(Res.string.pos_items_count, cart.sumOf { it.line.quantity }),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            if (cart.isEmpty()) {
                Text(
                    if (hasCustomer) stringResource(Res.string.pos_cart_empty)
                    else stringResource(Res.string.pos_select_customer_first),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                cart.take(3).forEach { entry ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "${entry.line.quantity}×",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(entry.line.name, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                        }
                        IconButton(
                            onClick = { onRemove(entry.key) },
                            enabled = !isProcessing,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onClear,
                    modifier = Modifier.weight(1f),
                    enabled = cart.isNotEmpty() && !isProcessing,
                    shape = PosTileShape
                ) {
                    Text(stringResource(Res.string.pos_clear_cart))
                }
                Button(
                    onClick = { if (!isProcessing) onValidate() },
                    enabled = enabled,
                    modifier = Modifier.weight(2f).height(48.dp),
                    shape = PosTileShape
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(Res.string.pos_sale_processing),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                        )
                    } else {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(Res.string.pos_validate_sale))
                    }
                }
            }
        }
    }
}
