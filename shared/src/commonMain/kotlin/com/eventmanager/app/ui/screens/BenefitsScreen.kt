


package com.eventmanager.app.ui.screens

import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import org.jetbrains.compose.resources.stringResource

import com.eventmanager.app.platform.LocalPlatformContext

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.eventmanager.app.data.models.*
import com.eventmanager.app.data.sync.settingsManagerFor
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.data.utils.groupFutureEntriesByInvites
import com.eventmanager.app.ui.components.BenefitsSystemHelpDialog
import com.eventmanager.app.ui.components.SearchBarWithFilter
import com.eventmanager.app.ui.utils.*

@Composable
fun getRankDisplayName(rank: VolunteerRank?): String {
    val platformContext = LocalPlatformContext.current
    return when (rank) {
        VolunteerRank.SPECIAL -> "✨SPECIAL✨"
        else -> rank?.name ?: stringResource(Res.string.no_rank)
    }
}

private fun perkBenefitForDisplay(
    status: VolunteerBenefitStatus,
    volunteerJobs: List<Job>,
    jobTypeConfigs: List<JobTypeConfig>,
    offsetHours: Int,
    currentTime: Long = System.currentTimeMillis(),
): Benefit {
    val excluded = BenefitCalculator.isVolunteerOrionActive(volunteerJobs, jobTypeConfigs, currentTime, offsetHours)
    val b = status.benefits
    if (!excluded) return b
    val leak = status.activeBenefits
        .asSequence()
        .filter { it.isNovaMeetingOnlyStylePerk() }
        .sumOf { it.drinkTokens }
    return if (leak <= 0) b else b.copy(drinkTokens = (b.drinkTokens - leak).coerceAtLeast(0))
}

private data class BenefitsDashboardAggregates(
    val activeBenefitsCount: Int,
    val with50Bar: Int,
    val withFreeEntry: Int,
    val guestSpotsWithFreeEntry: Int,
    val withExtraordinary: Int,
    val futureTicketsRemaining: Int,
    val futureInviteSlotsTotal: Int,
    val rankCounts: Map<VolunteerRank, Int>,
    val noRankCount: Int,
)

private fun computeBenefitsDashboardAggregates(
    rows: List<Pair<Volunteer, VolunteerBenefitStatus>>,
    jobsByVolunteerId: Map<String, List<Job>>,
    jobTypeConfigs: List<JobTypeConfig>,
    offsetHours: Int,
): BenefitsDashboardAggregates {
    val t = System.currentTimeMillis()
    val configsByName = jobTypeConfigs.associateBy { it.name }
    var active = 0
    var with50 = 0
    var withFree = 0
    var guestSpots = 0
    var withExtra = 0
    var futureTickets = 0
    var futureInvites = 0
    val rankMap = mutableMapOf<VolunteerRank, Int>()
    var noRank = 0
    rows.forEach { (volunteer, status) ->
        if (status.benefits.isActive) active++
        val vJobs = jobsByVolunteerId[volunteer.id].orEmpty()
        val perk = perkBenefitForDisplay(status, vJobs, jobTypeConfigs, offsetHours, t)
        if (perk.barDiscount > 0) with50++
        if (perk.freeEntry) {
            withFree++
            guestSpots += perk.inviteCount + if (perk.friendInvitation) 1 else 0
        }
        if (perk.extraordinaryBenefits) withExtra++
        val excluded = BenefitCalculator.isVolunteerOrionActive(vJobs, jobTypeConfigs, t, offsetHours)
        groupFutureEntriesByInvites(vJobs, configsByName, t, offsetHours, excluded).forEach { g ->
            futureTickets += g.totalRemaining
            futureInvites += g.totalRemaining * g.invites
        }
        when (val r = status.rank) {
            null -> noRank++
            else -> rankMap[r] = (rankMap[r] ?: 0) + 1
        }
    }
    return BenefitsDashboardAggregates(
        activeBenefitsCount = active,
        with50Bar = with50,
        withFreeEntry = withFree,
        guestSpotsWithFreeEntry = guestSpots,
        withExtraordinary = withExtra,
        futureTicketsRemaining = futureTickets,
        futureInviteSlotsTotal = futureInvites,
        rankCounts = rankMap.toMap(),
        noRankCount = noRank,
    )
}

private val benefitsOverviewRankOrder = listOf(
    VolunteerRank.ORION,
    VolunteerRank.VETERAN,
    VolunteerRank.GALAXIE,
    VolunteerRank.NOVA,
    VolunteerRank.ETOILE,
    VolunteerRank.SPECIAL,
)

@Composable
private fun rankOverviewAccent(rank: VolunteerRank): Color {
    val scheme = MaterialTheme.colorScheme
    return when (rank) {
        VolunteerRank.NOVA -> scheme.primary
        VolunteerRank.ETOILE -> scheme.secondary
        VolunteerRank.GALAXIE -> Color(0xFF7C3AED)
        VolunteerRank.ORION -> scheme.error
        VolunteerRank.VETERAN -> scheme.outline
        VolunteerRank.SPECIAL -> scheme.tertiary
    }
}

@Composable
private fun BenefitsOverviewStatLine(text: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = scheme.primary.copy(alpha = 0.55f)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun BenefitsRankOverviewRow(rank: VolunteerRank, count: Int) {
    val scheme = MaterialTheme.colorScheme
    val accent = rankOverviewAccent(rank)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = scheme.surfaceContainerHighest.copy(alpha = 0.55f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(22.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent)
            )
            Text(
                text = getRankDisplayName(rank),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = scheme.primaryContainer.copy(alpha = 0.55f)
            ) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun BenefitsOverviewNoRankRow(count: Int) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = scheme.surfaceContainerHighest.copy(alpha = 0.4f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(22.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(scheme.outlineVariant)
            )
            Text(
                text = getRankDisplayName(null),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = scheme.onSurfaceVariant
            )
        }
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable
private fun BenefitsOverviewDashboard(
    aggregates: BenefitsDashboardAggregates,
    horizontalSpacing: androidx.compose.ui.unit.Dp,
    verticalSpacing: androidx.compose.ui.unit.Dp,
    /** Unused: help is opened from [BenefitsScreenTitleRow]; kept so call sites can still pass this argument. */
    onOpenHelp: () -> Unit = {},
) {
    val platformContext = LocalPlatformContext.current
    val scheme = MaterialTheme.colorScheme
    val cardPad = getResponsiveCardPadding()
    val elev = getResponsiveCardElevation()
    val topRowMin = 88.dp
    val outerShape = RoundedCornerShape(18.dp)

    Column(verticalArrangement = Arrangement.spacedBy(verticalSpacing)) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = topRowMin),
            shape = outerShape,
            colors = CardDefaults.cardColors(containerColor = scheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = elev)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(cardPad),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = aggregates.activeBenefitsCount.toString(),
                    style = getResponsiveTypography(),
                    fontWeight = FontWeight.Bold,
                    color = scheme.primary
                )
                Text(
                    text = stringResource(Res.string.active_benefits),
                    style = getResponsiveBodyTypography(),
                    color = scheme.onSurfaceVariant
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
            verticalAlignment = Alignment.Top
        ) {
            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                shape = outerShape,
                colors = CardDefaults.cardColors(containerColor = scheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = elev)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(cardPad),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.benefits_overview_totals_section),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.primary
                    )
                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.6f))
                    BenefitsOverviewStatLine(
                        stringResource(Res.string.benefits_overview_line_50_bar, aggregates.with50Bar)
                    )
                    BenefitsOverviewStatLine(
                        stringResource(Res.string.benefits_overview_line_free_entry, aggregates.withFreeEntry,
                            aggregates.guestSpotsWithFreeEntry
                        )
                    )
                    BenefitsOverviewStatLine(
                        stringResource(Res.string.benefits_overview_line_extraordinary, aggregates.withExtraordinary
                        )
                    )
                    BenefitsOverviewStatLine(
                        stringResource(Res.string.benefits_overview_line_future_tickets, aggregates.futureTicketsRemaining,
                            aggregates.futureInviteSlotsTotal
                        )
                    )
                }
            }
            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                shape = outerShape,
                colors = CardDefaults.cardColors(containerColor = scheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = elev)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(cardPad),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.benefits_overview_ranks_section),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.primary
                    )
                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.6f))
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        benefitsOverviewRankOrder.forEach { rank ->
                            val c = aggregates.rankCounts[rank] ?: 0
                            if (c > 0) {
                                BenefitsRankOverviewRow(rank = rank, count = c)
                            }
                        }
                        if (aggregates.noRankCount > 0) {
                            BenefitsOverviewNoRankRow(count = aggregates.noRankCount)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BenefitsScreenTitleRow(
    title: String,
    onOpenHelp: () -> Unit,
) {
    val platformContext = LocalPlatformContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onOpenHelp) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                contentDescription = stringResource(Res.string.benefits_overview_info_button),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BenefitsScreen(
    volunteers: List<Volunteer>,
    jobs: List<Job> = emptyList(),
    jobTypeConfigs: List<JobTypeConfig> = emptyList(),
    scrollBehavior: String = SettingsManager.FULL_SCROLL
) {
    val platformContext = LocalPlatformContext.current
    val settingsManager = remember { com.eventmanager.app.data.sync.settingsManagerFor(platformContext) }
    val offsetHours = remember { settingsManager.getDateChangeOffsetHours() }
    var searchText by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf<String?>(null) }
    var showBenefitsHelp by remember { mutableStateOf(false) }
    
    val volunteerBenefits = remember(volunteers, jobs, jobTypeConfigs, offsetHours) {
        val currentTime = System.currentTimeMillis()
        volunteers.map { volunteer ->
            val status = BenefitCalculator.calculateVolunteerBenefitStatus(volunteer, jobs, jobTypeConfigs, currentTime = currentTime, offsetHours = offsetHours)
            volunteer to status
        }
    }
    val jobsByVolunteerId = remember(jobs) { jobs.groupBy { it.volunteerId } }

    val isCompact = isCompactScreen()
    val responsivePadding = getResponsivePadding()
    val responsiveSpacing = getResponsiveSpacing()

    // Memoize filter options to avoid recomputing on every recomposition
    val filterOptions = remember { VolunteerRank.values().map { it.name } }

    // Memoize filtered volunteer benefits to avoid recalculating on every recomposition
    val filteredVolunteerBenefits = remember(volunteerBenefits, searchText, selectedFilter) {
        val lowerSearchText = searchText.lowercase()
        volunteerBenefits.filter { (volunteer, status) ->
            val matchesSearch = searchText.isEmpty() || 
                volunteer.name.lowercase().contains(lowerSearchText) ||
                volunteer.email.lowercase().contains(lowerSearchText) ||
                volunteer.lastNameAbbreviation.lowercase().contains(lowerSearchText) ||
                volunteer.nfcCardUid.lowercase().contains(lowerSearchText)
            val matchesFilter = selectedFilter?.let { filter ->
                status.rank?.name == filter
            } ?: true
            matchesSearch && matchesFilter
        }.sortedBy { (volunteer, _) -> volunteer.name.lowercase() }
    }
    
    val dashboardStats = remember(filteredVolunteerBenefits, jobsByVolunteerId, jobTypeConfigs, offsetHours) {
        computeBenefitsDashboardAggregates(
            filteredVolunteerBenefits,
            jobsByVolunteerId,
            jobTypeConfigs,
            offsetHours
        )
    }

    if (showBenefitsHelp) {
        BenefitsSystemHelpDialog(onDismiss = { showBenefitsHelp = false })
    }

    when (scrollBehavior) {
        SettingsManager.HEADER_PINNED -> {
            // Header fixed, only list scrolls
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(responsivePadding)
            ) {
            // Header + help (top right, same row as title)
            BenefitsScreenTitleRow(
                title = if (isCompact) stringResource(Res.string.benefits_title) else stringResource(Res.string.volunteer_benefits_overview),
                onOpenHelp = { showBenefitsHelp = true },
            )
            
            Spacer(modifier = Modifier.height(if (isCompact) 4.dp else 8.dp))
            
            if (!isCompact) {
                Text(
                    text = stringResource(Res.string.benefits_description),
                    style = getResponsiveBodyTypography(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(responsiveSpacing))

            // Search and Filter Section
            SearchBarWithFilter(
                searchText = searchText,
                onSearchTextChange = { searchText = it },
                placeholder = stringResource(Res.string.search_volunteers_benefits_placeholder),
                filterOptions = filterOptions,
                selectedFilter = selectedFilter,
                onFilterChange = { selectedFilter = it }
            )

            Spacer(modifier = Modifier.height(16.dp))

            BenefitsOverviewDashboard(
                aggregates = dashboardStats,
                horizontalSpacing = responsiveSpacing,
                verticalSpacing = responsiveSpacing,
            )

            Spacer(modifier = Modifier.height(responsiveSpacing))

            // Benefits list - Use LazyColumn for lazy loading and better performance
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(responsiveSpacing),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(
                    items = filteredVolunteerBenefits,
                    key = { (volunteer, _) -> volunteer.id }
                ) { (volunteer, status) ->
                    BenefitCard(
                        volunteer = volunteer,
                        status = status,
                        volunteerJobs = jobsByVolunteerId[volunteer.id].orEmpty(),
                        jobTypeConfigs = jobTypeConfigs
                    )
                }
            }
            }
        }
        SettingsManager.STICKY_FILTERS -> {
            // Page scrolls but filters become sticky at the top
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(responsivePadding),
                verticalArrangement = Arrangement.spacedBy(responsiveSpacing)
            ) {
                // Header section (scrolls away)
                item {
                    BenefitsScreenTitleRow(
                        title = if (isCompact) stringResource(Res.string.benefits_title) else stringResource(Res.string.volunteer_benefits_overview),
                        onOpenHelp = { showBenefitsHelp = true },
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(if (isCompact) 4.dp else 8.dp))
                }

                if (!isCompact) {
                    item {
                        Text(
                            text = stringResource(Res.string.benefits_description),
                            style = getResponsiveBodyTypography(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(responsiveSpacing))
                }

                // Sticky filter section - becomes pinned when scrolled to top
                stickyHeader {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(bottom = 8.dp)
                    ) {
                        SearchBarWithFilter(
                            searchText = searchText,
                            onSearchTextChange = { searchText = it },
                            placeholder = stringResource(Res.string.search_volunteers_benefits_placeholder),
                            filterOptions = filterOptions,
                            selectedFilter = selectedFilter,
                            onFilterChange = { selectedFilter = it }
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }

                item {
                    BenefitsOverviewDashboard(
                        aggregates = dashboardStats,
                        horizontalSpacing = responsiveSpacing,
                        verticalSpacing = responsiveSpacing,
                    )
                }

                items(
                    items = filteredVolunteerBenefits,
                    key = { (volunteer, _) -> volunteer.id }
                ) { (volunteer, status) ->
                    BenefitCard(
                        volunteer = volunteer,
                        status = status,
                        volunteerJobs = jobsByVolunteerId[volunteer.id].orEmpty(),
                        jobTypeConfigs = jobTypeConfigs
                    )
                }
            }
        }
        else -> {
            // FULL_SCROLL: Whole page (header + list) scrolls together
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(responsivePadding),
                verticalArrangement = Arrangement.spacedBy(responsiveSpacing)
            ) {
                item {
                    BenefitsScreenTitleRow(
                        title = if (isCompact) stringResource(Res.string.benefits_title) else stringResource(Res.string.volunteer_benefits_overview),
                        onOpenHelp = { showBenefitsHelp = true },
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(if (isCompact) 4.dp else 8.dp))
                }

                if (!isCompact) {
                    item {
                        Text(
                            text = stringResource(Res.string.benefits_description),
                            style = getResponsiveBodyTypography(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(responsiveSpacing))
                }

                item {
                    SearchBarWithFilter(
                        searchText = searchText,
                        onSearchTextChange = { searchText = it },
                        placeholder = stringResource(Res.string.search_volunteers_benefits_placeholder),
                        filterOptions = filterOptions,
                        selectedFilter = selectedFilter,
                        onFilterChange = { selectedFilter = it }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }

                item {
                    BenefitsOverviewDashboard(
                        aggregates = dashboardStats,
                        horizontalSpacing = responsiveSpacing,
                        verticalSpacing = responsiveSpacing,
                    )
                }

                items(
                    items = filteredVolunteerBenefits,
                    key = { (volunteer, _) -> volunteer.id }
                ) { (volunteer, status) ->
                    BenefitCard(
                        volunteer = volunteer,
                        status = status,
                        volunteerJobs = jobsByVolunteerId[volunteer.id].orEmpty(),
                        jobTypeConfigs = jobTypeConfigs
                    )
                }
            }
        }
    }
}

private data class FutureEntryDisplayGroup(val invites: Int, val remaining: Int)

@Composable
fun BenefitCard(
    volunteer: Volunteer,
    status: VolunteerBenefitStatus,
    volunteerJobs: List<Job>,
    jobTypeConfigs: List<JobTypeConfig>,
    modifier: Modifier = Modifier
) {
    val platformContext = LocalPlatformContext.current
    val benefit = status.benefits
    val configsByName = remember(jobTypeConfigs) { jobTypeConfigs.associateBy { it.name } }
    val settingsManager = remember { com.eventmanager.app.data.sync.settingsManagerFor(platformContext) }
    val offsetHours = remember { settingsManager.getDateChangeOffsetHours() }
    val benefitForPerkList = remember(
        status.benefits,
        status.activeBenefits,
        volunteerJobs,
        jobTypeConfigs,
        offsetHours
    ) {
        perkBenefitForDisplay(status, volunteerJobs, jobTypeConfigs, offsetHours)
    }
    // Keep the exact same source of truth as guest list / volunteer profile:
    // aggregate from tracked job balances, grouped by invite count.
    val futureEntryGroups = remember(volunteerJobs, configsByName, offsetHours, jobTypeConfigs) {
        val t = System.currentTimeMillis()
        val excluded = BenefitCalculator.isVolunteerOrionActive(volunteerJobs, jobTypeConfigs, t, offsetHours)
        groupFutureEntriesByInvites(volunteerJobs, configsByName, t, offsetHours, excluded)
            .map { FutureEntryDisplayGroup(invites = it.invites, remaining = it.totalRemaining) }
    }
    val totalFutureEntriesRemaining = remember(futureEntryGroups) { futureEntryGroups.sumOf { it.remaining } }
    val isCompact = isCompactScreen()
    val responsivePadding = getResponsiveCardPadding()
    val responsiveAvatarSize = getResponsiveAvatarSize()
    
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = getResponsiveCardElevation())
    ) {
        Column(
            modifier = Modifier.padding(responsivePadding)
        ) {
            // Volunteer header with avatar-like design
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar circle
                Card(
                    modifier = Modifier.size(responsiveAvatarSize),
                    shape = androidx.compose.foundation.shape.CircleShape,
                    colors = CardDefaults.cardColors(
                        containerColor = when (status.rank) {
                            VolunteerRank.NOVA -> MaterialTheme.colorScheme.primary
                            VolunteerRank.ETOILE -> MaterialTheme.colorScheme.secondary
                            VolunteerRank.GALAXIE -> Color(0xFF7C3AED) // Deep purple for galaxy - always readable
                            VolunteerRank.ORION -> MaterialTheme.colorScheme.error
                            VolunteerRank.VETERAN -> MaterialTheme.colorScheme.surfaceVariant
                            VolunteerRank.SPECIAL -> MaterialTheme.colorScheme.primaryContainer
                            null -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = volunteer.name.take(1).uppercase(),
                            style = if (isCompact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = when (status.rank) {
                                VolunteerRank.NOVA -> MaterialTheme.colorScheme.onPrimary
                                VolunteerRank.ETOILE -> MaterialTheme.colorScheme.onSecondary
                                VolunteerRank.GALAXIE -> Color.White // White on deep purple - always readable
                                VolunteerRank.ORION -> MaterialTheme.colorScheme.onError
                                VolunteerRank.VETERAN -> MaterialTheme.colorScheme.onSurfaceVariant
                                VolunteerRank.SPECIAL -> MaterialTheme.colorScheme.onPrimaryContainer
                                null -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.width(if (isCompact) 8.dp else 12.dp))
                
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = volunteer.name,
                        style = getResponsiveTitleTypography(),
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${volunteer.lastNameAbbreviation} • ${volunteer.email}",
                        style = getResponsiveBodyTypography(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Rank badge with status
                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    AssistChip(
                        onClick = { },
                        label = { Text(getRankDisplayName(status.rank)) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = when (status.rank) {
                                VolunteerRank.NOVA -> MaterialTheme.colorScheme.primaryContainer
                                VolunteerRank.ETOILE -> MaterialTheme.colorScheme.secondaryContainer
                                VolunteerRank.GALAXIE -> Color(0xFFEDE9FE) // Light purple container - always readable
                                VolunteerRank.ORION -> MaterialTheme.colorScheme.errorContainer
                                VolunteerRank.VETERAN -> MaterialTheme.colorScheme.surfaceVariant
                                VolunteerRank.SPECIAL -> MaterialTheme.colorScheme.primaryContainer
                                null -> MaterialTheme.colorScheme.surfaceVariant
                            },
                            labelColor = if (status.rank == VolunteerRank.GALAXIE) Color(0xFF5B21B6) else Color.Unspecified,
                            leadingIconContentColor = if (status.rank == VolunteerRank.GALAXIE) Color(0xFF5B21B6) else Color.Unspecified
                        )
                    )
                    
                    // Status indicator
                    if (!benefit.isActive) {
                        Text(
                            text = stringResource(Res.string.expired),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else if (benefit.validUntil != null) {
                        val timeLeft = benefit.validUntil - System.currentTimeMillis()
                        val daysLeft = timeLeft / (1000 * 60 * 60 * 24)
                        Text(
                            text = if (daysLeft > 0) stringResource(Res.string.days_left, daysLeft) else stringResource(Res.string.expires_soon),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (daysLeft > 7) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(if (isCompact) 12.dp else 16.dp))
            
            // Benefit details section with header
            Column(
                verticalArrangement = Arrangement.spacedBy(if (isCompact) 8.dp else 12.dp)
            ) {
                // Section header
                Text(
                    text = stringResource(Res.string.benefit_details),
                    style = if (isCompact) MaterialTheme.typography.labelLarge else getResponsiveTitleTypography(),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                
                // Benefit details list
                Column(
                    verticalArrangement = Arrangement.spacedBy(if (isCompact) 4.dp else 8.dp)
                ) {
                    listOfNotNull(
                        entryPerkText(benefitForPerkList),
                        if (benefitForPerkList.drinkTokens > 0) stringResource(Res.string.drink_tokens, benefitForPerkList.drinkTokens) else null,
                        if (benefitForPerkList.barDiscount > 0) stringResource(Res.string.bar_discount, benefitForPerkList.barDiscount) else null,
                        if (benefitForPerkList.extraordinaryBenefits) stringResource(Res.string.extraordinary_benefits) else null
                    ).forEach { benefitText ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = if (isCompact) 1.dp else 2.dp)
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(if (isCompact) 14.dp else 16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(if (isCompact) 6.dp else 8.dp))
                            Text(
                                text = benefitText,
                                style = if (isCompact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    if (totalFutureEntriesRemaining > 0) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = if (isCompact) 4.dp else 6.dp))
                        futureEntryGroups.forEach { group ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = if (isCompact) 1.dp else 2.dp)
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(if (isCompact) 14.dp else 16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(if (isCompact) 6.dp else 8.dp))
                                val label = if (group.invites > 0) {
                                    if (group.remaining == 1) stringResource(Res.string.future_entry_remaining_with_invites, group.remaining, companionsText(group.invites))
                                    else stringResource(Res.string.future_entries_remaining_with_invites, group.remaining, companionsText(group.invites))
                                } else {
                                    if (group.remaining == 1) stringResource(Res.string.future_entry_solo, group.remaining)
                                    else stringResource(Res.string.future_entries_solo, group.remaining)
                                }
                                Text(
                                    text = label,
                                    style = if (isCompact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}




