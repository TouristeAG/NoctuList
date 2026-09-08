package com.eventmanager.app.platform.hardware

import com.eventmanager.app.data.sync.AppLogger
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.platform.UidReadResult
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.desktop_pcsc_beep_no_uid_hint
import com.eventmanager.app.resources.desktop_pcsc_ble_softdevice_uid_hint
import com.eventmanager.app.resources.desktop_pcsc_error
import com.eventmanager.app.resources.desktop_pcsc_escape_blocked_hint
import com.eventmanager.app.resources.desktop_pcsc_ghost_reader_hint
import com.eventmanager.app.resources.desktop_pcsc_library_unavailable
import com.eventmanager.app.resources.desktop_pcsc_list_error
import com.eventmanager.app.resources.desktop_pcsc_no_ble_reader
import com.eventmanager.app.resources.desktop_pcsc_no_ble_reader_paired_hint
import com.eventmanager.app.resources.desktop_pcsc_no_reader
import com.eventmanager.app.resources.desktop_pcsc_no_readers_found
import com.eventmanager.app.resources.desktop_pcsc_no_readers_hint
import com.eventmanager.app.resources.desktop_pcsc_provider_failed
import com.eventmanager.app.resources.desktop_pcsc_reader_disconnected
import com.eventmanager.app.resources.desktop_pcsc_reader_error
import com.eventmanager.app.resources.desktop_pcsc_reader_ok_place_card
import com.eventmanager.app.resources.desktop_pcsc_readers_header
import com.eventmanager.app.resources.desktop_pcsc_service_hint_windows
import com.eventmanager.app.resources.desktop_pcsc_unable_select_reader
import com.eventmanager.app.resources.desktop_pcsc_unavailable
import com.eventmanager.app.resources.desktop_pcsc_using_reader
import com.eventmanager.app.resources.desktop_pcsc_waiting_card
import jnasmartcardio.Smartcardio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import java.security.Security
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import javax.smartcardio.Card
import javax.smartcardio.CardException
import javax.smartcardio.CardNotPresentException
import javax.smartcardio.CardTerminal
import javax.smartcardio.CardTerminals
import javax.smartcardio.CommandAPDU
import javax.smartcardio.ResponseAPDU
import javax.smartcardio.TerminalFactory

/**
 * PC/SC NFC readers on desktop: USB (ACR122U, ACR1255U-J1 USB) and Bluetooth (ACR1255U-J1
 * after OS pairing on Windows).
 *
 * Uses a **process-wide** Winscard context (jnasmartcardio). Recreating a TerminalFactory on every
 * poll makes Windows reader detection flaky when the Smart Card service restarts on plug/unplug.
 */
class DesktopPcscCardReader {

    data class DiagnosticResult(val success: Boolean, val details: String)

    data class TerminalInfo(
        val terminal: CardTerminal,
        val name: String,
        val kind: ReaderKind
    )

    enum class ReaderKind { USB, BLE, OTHER }

    private val accessMutex = Mutex()

    @Volatile private var lastListError: String? = null
    @Volatile private var lastDispatchedUid: String? = null
    @Volatile private var lastDispatchAtMs: Long = 0L
    @Volatile private var awaitingCardRemoval: Boolean = false
    @Volatile private var awaitingRemovalSinceMs: Long = 0L
    @Volatile private var piccPollingEnsuredAtMs: Long = 0L
    @Volatile private var lastWiredSeenAtMs: Long = 0L
    @Volatile private var lastWiredNames: List<String> = emptyList()
    private val lastProbeNotes = mutableListOf<String>()

    /** Bound SoftDevice Escape so a stuck Winscard call cannot freeze the scan loop. */
    private val softDeviceIo = Executors.newCachedThreadPool { r ->
        Thread(r, "noctulist-softdevice-escape").apply { isDaemon = true }
    }

    /** Last probe details for diagnostics (presence / APDU / Escape). */
    fun lastProbeNotesSnapshot(): String = lastProbeNotes.joinToString("; ")

    /**
     * Run a light PC/SC list/status probe only when a UID read is not in flight.
     * Concurrent Winscard list + SoftDevice Escape can stall on Windows.
     */
    fun tryWithIdlePcscAccess(block: () -> Unit): Boolean {
        if (!accessMutex.tryLock()) return false
        return try {
            block()
            true
        } finally {
            accessMutex.unlock()
        }
    }

    private fun tr(block: suspend () -> String): String = runBlocking { block() }

    fun isReaderAvailable(): Boolean = isPcscAvailable() && listTerminals().isNotEmpty()

    /** USB or other non-BLE PC/SC terminals suitable for contactless UID reads. */
    fun hasWiredReader(): Boolean {
        // macOS: exact pre-Windows behaviour — any PC/SC terminal classified USB.
        if (!isWindowsOs()) {
            return listTerminalInfos().any { it.kind == ReaderKind.USB }
        }
        val infos = listTerminalInfos()
        val wired = infos.filter { it.kind == ReaderKind.USB && !isGhostOrBleSoftDevice(it.name) }
        val found = pickPreferredContactless(wired) != null
        if (found) {
            lastWiredSeenAtMs = System.currentTimeMillis()
            lastWiredNames = wired.map { it.name }
            return true
        }
        // Only sticky if those wired names are still present in the live list.
        if (lastWiredNames.isNotEmpty() &&
            System.currentTimeMillis() - lastWiredSeenAtMs < WIRED_STICKY_MS
        ) {
            val liveNames = infos.map { it.name }.toSet()
            if (lastWiredNames.any { it in liveNames }) return true
        }
        lastWiredNames = emptyList()
        return false
    }

    fun hasUsbReader(): Boolean = hasWiredReader()

    fun firstUsbTerminalName(): String? =
        if (!isWindowsOs()) {
            listTerminalInfos().firstOrNull { it.kind == ReaderKind.USB }?.name
        } else {
            preferredWiredTerminal()?.name
        }

    fun readerName(): String = selectTerminal(null)?.name ?: tr {
        if (isPcscAvailable()) getString(Res.string.desktop_pcsc_no_reader)
        else getString(Res.string.desktop_pcsc_unavailable)
    }

    fun listReaderNames(): List<String> = listTerminals().map { it.name }

    fun listTerminalInfos(): List<TerminalInfo> = listTerminals().map { terminal ->
        val name = terminal.name.orEmpty()
        TerminalInfo(terminal = terminal, name = name, kind = classifyTerminal(name))
    }

    fun formatTerminalListing(): String = tr {
        val error = lastListError
        val terminals = listTerminalInfos()
        if (terminals.isEmpty()) {
            return@tr buildString {
                appendLine(getString(Res.string.desktop_pcsc_no_readers_found))
                if (!error.isNullOrBlank()) {
                    appendLine(getString(Res.string.desktop_pcsc_list_error, error))
                }
                if (isWindowsOs()) {
                    appendLine(getString(Res.string.desktop_pcsc_service_hint_windows))
                }
            }.trimEnd()
        }
        buildString {
            appendLine(getString(Res.string.desktop_pcsc_readers_header, terminals.size))
            terminals.forEach { info ->
                val tag = when (info.kind) {
                    ReaderKind.USB -> "USB"
                    ReaderKind.BLE -> "BLE"
                    ReaderKind.OTHER -> "PC/SC"
                }
                val piccNote = when {
                    isContactInterfaceOnly(info.name) -> " (ICC — skipped for NFC)"
                    isGhostOrBleSoftDevice(info.name) -> " (BLE SoftDevice — not USB)"
                    isPreferredContactless(info.name) -> " (PICC)"
                    else -> ""
                }
                appendLine(" • [$tag] ${info.name}$piccNote")
            }
        }.trimEnd()
    }

    fun findBleTerminal(savedReaderId: String, savedReaderName: String): CardTerminal? =
        selectBleTerminal(savedReaderId, savedReaderName)

    fun singleAutoBleTerminalId(): String? {
        val ble = contactlessOfKind(ReaderKind.BLE)
        if (ble.size != 1) return null
        return terminalId(ble.first().name)
    }

    fun clearBleSession() {
        awaitingCardRemoval = false
        awaitingRemovalSinceMs = 0L
        piccPollingEnsuredAtMs = 0L
        lastDispatchedUid = null
        lastDispatchAtMs = 0L
    }

    /**
     * After the user picks a SoftDevice reader: open DIRECT briefly, enable BLE auto-poll,
     * then disconnect so RF scanning starts. Holding a long-lived Card prevents SoftDevice
     * from scanning until much later.
     */
    suspend fun warmBleSoftDevice(settings: SettingsManager): Boolean = accessMutex.withLock {
        withContext(Dispatchers.IO) {
            val terminal = selectBleTerminal(
                DesktopExternalNfcReader.resolveBleReaderId(settings),
                settings.getExternalBleReaderName(),
            ) ?: return@withContext false
            piccPollingEnsuredAtMs = 0L
            val card = runCatching { terminal.connect("direct") }.getOrNull()
                ?: runCatching { terminal.connect("EXCLUSIVE;direct") }.getOrNull()
                ?: return@withContext false
            try {
                Thread.sleep(SOFTDEVICE_POST_CONNECT_MS)
                val opened = transmitEscape(
                    card,
                    byteArrayOf(0xE0.toByte(), 0x00, 0x00, 0x40, 0x01),
                    softFast = true,
                ) != null
                lastProbeNotes.clear()
                lastProbeNotes += if (opened) "warmBlePoll=ok" else "warmBlePoll=skip"
                piccPollingEnsuredAtMs = System.currentTimeMillis()
                opened
            } finally {
                runCatching { card.disconnect(false) }
            }
        }
    }

    suspend fun readUid(settings: SettingsManager? = null): UidReadResult = accessMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!isPcscAvailable()) {
                return@withContext UidReadResult.Fatal(
                    getString(Res.string.desktop_pcsc_library_unavailable)
                )
            }
            var terminal = selectTerminal(settings)
            if (terminal == null && hasWiredReader()) {
                refreshPcscListing()
                terminal = selectTerminal(settings)
            }
            if (terminal == null) return@withContext UidReadResult.NoReader
            readUidFromTerminal(terminal, cardWaitMs = CARD_WAIT_MS)
        }
    }

    suspend fun runDiagnostic(): DiagnosticResult = accessMutex.withLock {
        withContext(Dispatchers.IO) {
            // Always re-resolve the terminal inside the lock (fresh PC/SC list).
            runDiagnosticInternal(selectTerminal(null), bleOnly = false)
        }
    }

    suspend fun runBleDiagnostic(settings: SettingsManager): DiagnosticResult = accessMutex.withLock {
        withContext(Dispatchers.IO) {
            val terminal = selectBleTerminal(
                DesktopExternalNfcReader.resolveBleReaderId(settings),
                settings.getExternalBleReaderName()
            )
            runDiagnosticInternal(terminal, bleOnly = true)
        }
    }

    private suspend fun runDiagnosticInternal(
        terminal: CardTerminal?,
        bleOnly: Boolean
    ): DiagnosticResult {
        val details = StringBuilder()
        if (!isPcscAvailable()) {
            return DiagnosticResult(
                success = false,
                details = buildString {
                    appendLine(getString(Res.string.desktop_pcsc_provider_failed))
                    PcscContext.lastInitError?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
                    if (isWindowsOs()) {
                        appendLine(getString(Res.string.desktop_pcsc_service_hint_windows))
                    }
                }.trimEnd()
            )
        }
        // Force a context refresh so a just-plugged reader is visible.
        refreshPcscListing()
        val terminals = listTerminalInfos()
        if (terminals.isEmpty()) {
            val emptyMsg = if (bleOnly) {
                getString(Res.string.desktop_pcsc_no_ble_reader)
            } else {
                getString(Res.string.desktop_pcsc_no_readers_hint)
            }
            return DiagnosticResult(
                success = false,
                details = buildString {
                    appendLine(emptyMsg)
                    lastListError?.takeIf { it.isNotBlank() }?.let {
                        appendLine(getString(Res.string.desktop_pcsc_list_error, it))
                    }
                    if (bleOnly && isWindowsOs()) {
                        appendLine(getString(Res.string.desktop_pcsc_no_ble_reader_paired_hint))
                    }
                    if (isWindowsOs()) {
                        appendLine(getString(Res.string.desktop_pcsc_service_hint_windows))
                    }
                }.trimEnd()
            )
        }
        details.appendLine(getString(Res.string.desktop_pcsc_readers_header, terminals.size))
        terminals.forEach { info ->
            val tag = when (info.kind) {
                ReaderKind.USB -> "USB"
                ReaderKind.BLE -> "BLE"
                ReaderKind.OTHER -> "PC/SC"
            }
            val extra = when {
                isGhostOrBleSoftDevice(info.name) -> " (BLE SoftDevice)"
                isPreferredContactless(info.name) -> " (PICC)"
                isContactInterfaceOnly(info.name) -> " (ICC)"
                else -> ""
            }
            details.appendLine(" • [$tag] ${info.name}$extra")
        }

        val candidates: List<CardTerminal> = if (bleOnly) {
            val selectedName = terminal?.name
            val selected = when {
                selectedName.isNullOrBlank() -> null
                else -> listTerminals().firstOrNull { it.name == selectedName } ?: terminal
            }
            listOfNotNull(selected)
        } else {
            val wired = wiredDiagnosticCandidates().map { it.terminal }
            if (wired.isNotEmpty()) wired
            else listOfNotNull(
                preferredWiredTerminal()?.terminal
            )
        }

        if (bleOnly && candidates.isEmpty() && terminals.none { it.kind == ReaderKind.BLE }) {
            details.appendLine(getString(Res.string.desktop_pcsc_no_ble_reader_paired_hint))
        }
        if (candidates.isEmpty()) {
            return DiagnosticResult(
                success = false,
                details = details.appendLine(getString(Res.string.desktop_pcsc_unable_select_reader))
                    .toString().trimEnd()
            )
        }

        // Drop a stuck "wait for removal" gate so a test always tries a real UID read.
        awaitingCardRemoval = false
        awaitingRemovalSinceMs = 0L
        piccPollingEnsuredAtMs = 0L

        var result: UidReadResult = UidReadResult.Retryable(ERR_NO_CARD_PRESENT)
        var sawCardPresent = false
        var usedName = candidates.first().name.orEmpty()

        for (candidate in candidates) {
            usedName = candidate.name.orEmpty()
            details.appendLine(getString(Res.string.desktop_pcsc_using_reader, usedName))
            details.appendLine(
                getString(Res.string.desktop_pcsc_waiting_card, DIAGNOSTIC_CARD_WAIT_MS / 1000)
            )

            val deadline = System.currentTimeMillis() + DIAGNOSTIC_CARD_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                val presentNow = runCatching { candidate.isCardPresent }.getOrDefault(false)
                if (presentNow) sawCardPresent = true
                result = readUidFromTerminal(candidate, cardWaitMs = DIAGNOSTIC_POLL_MS)
                when (result) {
                    is UidReadResult.Success -> break
                    is UidReadResult.Fatal -> break
                    is UidReadResult.Retryable -> {
                        // Escape blocked: try enabling polling already done — keep trying shared
                        // Get UID while the card is held; then fall through to next candidate.
                        val retryable = result.error == ERR_NO_CARD_PRESENT ||
                            result.error == ERR_NO_CARD_ON_READER ||
                            result.error == ERR_WAITING_REMOVAL ||
                            result.error == ERR_ESCAPE_BLOCKED ||
                            result.error == ERR_BLE_SOFTDEVICE_NO_UID ||
                            result.error?.startsWith("No card detected (SW=") == true ||
                            result.error?.startsWith("Connect failed") == true ||
                            result.error?.startsWith("PN532 connect failed") == true
                        if (!retryable) break
                        delay(200)
                    }
                    UidReadResult.NoReader -> break
                }
                if (result is UidReadResult.Success) break
            }
            if (result is UidReadResult.Success) break
            val notes = lastProbeNotesSnapshot()
            if (notes.isNotBlank()) {
                details.appendLine("Probe ($usedName): $notes")
            }
            if (candidates.size > 1) {
                details.appendLine("— next reader —")
            }
        }

        val notes = lastProbeNotesSnapshot()
        if (notes.isNotBlank() && !details.toString().contains("Probe (")) {
            details.appendLine("Probe: $notes")
        }

        return when (result) {
            is UidReadResult.Success -> {
                details.appendLine("UID: ${result.uid}")
                DiagnosticResult(success = true, details = details.toString().trimEnd())
            }
            is UidReadResult.Retryable -> {
                when {
                    result.error == ERR_ESCAPE_BLOCKED ||
                        result.error == ERR_BLE_SOFTDEVICE_NO_UID -> {
                        details.appendLine(result.error)
                        if (bleOnly || candidates.any { isGhostOrBleSoftDevice(it.name.orEmpty()) } ||
                            result.error == ERR_BLE_SOFTDEVICE_NO_UID
                        ) {
                            details.appendLine(getString(Res.string.desktop_pcsc_ble_softdevice_uid_hint))
                        } else {
                            details.appendLine(getString(Res.string.desktop_pcsc_escape_blocked_hint))
                            details.appendLine(getString(Res.string.desktop_pcsc_ghost_reader_hint))
                        }
                        DiagnosticResult(success = false, details = details.toString().trimEnd())
                    }
                    result.error == ERR_NO_CARD_PRESENT ||
                        result.error == ERR_NO_CARD_ON_READER -> {
                        if (sawCardPresent) {
                            details.appendLine(
                                result.error ?: getString(Res.string.desktop_pcsc_reader_error)
                            )
                            details.appendLine(
                                "Card was present on PC/SC but Get UID failed — check PICC interface."
                            )
                            DiagnosticResult(success = false, details = details.toString().trimEnd())
                        } else {
                            details.appendLine(getString(Res.string.desktop_pcsc_reader_ok_place_card))
                            details.appendLine(getString(Res.string.desktop_pcsc_beep_no_uid_hint))
                            if (bleOnly || usedName.let { isGhostOrBleSoftDevice(it) }) {
                                details.appendLine(getString(Res.string.desktop_pcsc_ble_softdevice_uid_hint))
                            } else {
                                details.appendLine(getString(Res.string.desktop_pcsc_ghost_reader_hint))
                            }
                            DiagnosticResult(success = false, details = details.toString().trimEnd())
                        }
                    }
                    else -> DiagnosticResult(
                        success = false,
                        details = details.appendLine(
                            result.error ?: getString(Res.string.desktop_pcsc_reader_error)
                        ).toString().trimEnd()
                    )
                }
            }
            is UidReadResult.Fatal -> DiagnosticResult(
                success = false,
                details = details.appendLine(
                    result.error ?: getString(Res.string.desktop_pcsc_reader_error)
                ).toString().trimEnd()
            )
            UidReadResult.NoReader -> DiagnosticResult(
                success = false,
                details = details.appendLine(getString(Res.string.desktop_pcsc_reader_disconnected))
                    .toString().trimEnd()
            )
        }
    }

    private fun isPcscAvailable(): Boolean =
        if (isWindowsOs()) PcscContext.isAvailable() else ensureClassicPcscProvider()

    private fun refreshPcscListing() {
        if (isWindowsOs()) {
            PcscContext.refresh()
        }
        // macOS: TerminalFactory.getDefault() already re-lists CryptoTokenKit on each call.
    }

    /**
     * macOS / Linux: use [TerminalFactory.getDefault] exactly like the pre-Windows rewrite.
     * Persistent Winscard contexts and SoftDevice heuristics broke USB discovery on macOS.
     */
    private fun ensureClassicPcscProvider(): Boolean = try {
        if (Security.getProvider(Smartcardio.PROVIDER_NAME) == null) {
            Security.insertProviderAt(Smartcardio(), 1)
        }
        TerminalFactory.getDefault()
        true
    } catch (e: Throwable) {
        AppLogger.e(LOG_TAG, "Classic PC/SC provider init failed", e)
        false
    }

    private fun listTerminals(): List<CardTerminal> {
        if (!isWindowsOs()) {
            lastListError = null
            return runCatching {
                ensureClassicPcscProvider()
                TerminalFactory.getDefault().terminals().list()
            }.getOrElse { e ->
                lastListError = e.message ?: e.javaClass.simpleName
                emptyList()
            }
        }
        if (!PcscContext.isAvailable()) {
            lastListError = "PC/SC provider unavailable"
            return emptyList()
        }
        // Never Thread.sleep here — UI used to call this on the EDT and freezes for hundreds of ms.
        // One reset+retry on error is enough for hot-plug races.
        val first = PcscContext.listTerminals()
        if (first.terminals.isNotEmpty()) {
            lastListError = null
            return first.terminals
        }
        if (first.error != null) {
            lastListError = first.error
            PcscContext.reset()
            val second = PcscContext.listTerminals()
            if (second.terminals.isNotEmpty()) {
                lastListError = null
                return second.terminals
            }
            lastListError = second.error ?: first.error
            return emptyList()
        }
        // Empty with no error: refresh context once (service may have just started) without sleeping.
        PcscContext.refresh()
        val refreshed = PcscContext.listTerminals()
        if (refreshed.terminals.isNotEmpty()) {
            lastListError = null
            return refreshed.terminals
        }
        lastListError = refreshed.error
        return emptyList()
    }

    private fun selectTerminal(settings: SettingsManager?): CardTerminal? {
        if (!isWindowsOs()) {
            val infos = listTerminalInfos()
            infos.firstOrNull { it.kind == ReaderKind.USB }?.terminal?.let { return it }
            infos.firstOrNull { it.kind == ReaderKind.OTHER }?.terminal?.let { return it }
            val savedId = settings?.getExternalBleReaderMac()?.trim().orEmpty()
            val savedName = settings?.getExternalBleReaderName()?.trim().orEmpty()
            return selectBleTerminal(savedId, savedName)
        }
        // Never fall back to ICC-only interfaces — contactless UID needs PICC / non-ICC.
        preferredWiredTerminal()?.terminal?.let { return it }

        val savedId = settings?.getExternalBleReaderMac()?.trim().orEmpty()
        val savedName = settings?.getExternalBleReaderName()?.trim().orEmpty()
        return selectBleTerminal(savedId, savedName)
    }

    private fun preferredWiredTerminal(): TerminalInfo? {
        val infos = listTerminalInfos()
        val usb = infos.filter {
            it.kind == ReaderKind.USB && !isGhostOrBleSoftDevice(it.name)
        }
        pickPreferredContactless(usb)?.let { return it }
        val other = infos.filter {
            it.kind == ReaderKind.OTHER &&
                !isGhostOrBleSoftDevice(it.name) &&
                !isContactInterfaceOnly(it.name)
        }
        return pickPreferredContactless(other)
    }

    /** Wired candidates for USB diagnostics (PICC first, SoftDevice BLE ghosts excluded). */
    private fun wiredDiagnosticCandidates(): List<TerminalInfo> {
        val infos = listTerminalInfos()
        val usb = infos.filter {
            it.kind == ReaderKind.USB &&
                !isContactInterfaceOnly(it.name) &&
                !isGhostOrBleSoftDevice(it.name)
        }
        val preferred = usb.filter { isPreferredContactless(it.name) }
        val rest = usb.filterNot { isPreferredContactless(it.name) }
        return preferred + rest
    }

    private fun contactlessOfKind(kind: ReaderKind): List<TerminalInfo> =
        listTerminalInfos().filter { it.kind == kind && !isContactInterfaceOnly(it.name) }

    /**
     * Prefer PICC / contactless interfaces; never pick ICC-only (contact/SAM) when a better
     * terminal exists. Windows ACS drivers often list ICC before PICC alphabetically.
     */
    private fun pickPreferredContactless(candidates: List<TerminalInfo>): TerminalInfo? {
        if (candidates.isEmpty()) return null
        val usable = candidates.filterNot { isContactInterfaceOnly(it.name) }
        if (usable.isEmpty()) return null
        usable.firstOrNull { isPreferredContactless(it.name) }?.let { return it }
        return usable.first()
    }

    private fun selectBleTerminal(savedReaderId: String, savedReaderName: String): CardTerminal? {
        val bleTerminals = contactlessOfKind(ReaderKind.BLE)
        if (bleTerminals.isEmpty()) return null

        if (savedReaderId.isNotBlank()) {
            bleTerminals.firstOrNull { terminalMatchesId(it, savedReaderId) }?.terminal?.let { return it }
            if (!isPcscTerminalId(savedReaderId) && bleTerminals.size == 1) {
                return bleTerminals.first().terminal
            }
        }
        if (savedReaderName.isNotBlank()) {
            val hint = savedReaderName.lowercase(Locale.US)
            bleTerminals.firstOrNull { it.name.lowercase(Locale.US).contains(hint) }?.terminal
                ?.let { return it }
            if (nameLooksLikeAcr1255(savedReaderName)) {
                bleTerminals.firstOrNull { nameLooksLikeAcr1255(it.name) }?.terminal?.let { return it }
            }
        }
        if (bleTerminals.size == 1) return bleTerminals.first().terminal
        return null
    }

    private fun terminalMatchesId(info: TerminalInfo, savedReaderId: String): Boolean {
        if (savedReaderId.equals(info.name, ignoreCase = true)) return true
        if (savedReaderId.equals(terminalId(info.name), ignoreCase = true)) return true
        if (savedReaderId.startsWith(PCSC_ID_PREFIX, ignoreCase = true)) {
            val expected = savedReaderId.substring(PCSC_ID_PREFIX.length)
            return info.name.equals(expected, ignoreCase = true)
        }
        val normalizedMac = savedReaderId.uppercase(Locale.US)
        val compactMac = normalizedMac.replace(":", "").replace("-", "")
        val upperName = info.name.uppercase(Locale.US)
        return upperName.contains(normalizedMac.replace(':', '-')) ||
            upperName.contains(normalizedMac) ||
            (compactMac.length == 12 && upperName.replace(":", "").replace("-", "").contains(compactMac))
    }

    private fun readUidFromTerminal(
        terminal: CardTerminal,
        cardWaitMs: Long = CARD_WAIT_MS
    ): UidReadResult {
        return try {
            val live = resolveLiveTerminal(terminal)
                ?: return UidReadResult.NoReader

            if (!passCardRemovalGate(live)) {
                return UidReadResult.Retryable(ERR_WAITING_REMOVAL)
            }

            lastProbeNotes.clear()
            if (isBleishTerminal(live.name.orEmpty())) {
                return readUidSoftDeviceFast(live)
            }
            // macOS CryptoTokenKit + ACR USB worked with the classic PC/SC path (T=CL, Le=04).
            // The Windows multi-strategy path (direct/escape/PN532) broke that — keep it for Win only.
            if (!isWindowsOs()) {
                return readUidWiredClassic(live, cardWaitMs)
            }
            readUidWired(live, cardWaitMs)
        } catch (_: CardNotPresentException) {
            UidReadResult.Retryable(ERR_NO_CARD_PRESENT)
        } catch (e: CardException) {
            UidReadResult.Retryable(e.message ?: tr { getString(Res.string.desktop_pcsc_error) })
        } catch (e: Exception) {
            UidReadResult.Fatal(e.message ?: tr { getString(Res.string.desktop_pcsc_error) })
        }
    }

    /**
     * Classic USB UID read used before the Windows PC/SC rewrite. Restored for macOS/Linux:
     * waitForCardPresent → connect (T=CL first) → Get UID (Le=04 first) → disconnect(reset).
     */
    private fun readUidWiredClassic(terminal: CardTerminal, cardWaitMs: Long): UidReadResult {
        lastProbeNotes += "path=wired-classic"
        val waitMs = maxOf(cardWaitMs, CLASSIC_CARD_WAIT_MS)
        val cardPresent = runCatching { terminal.waitForCardPresent(waitMs) }
            .getOrElse { e ->
                if (e is CardNotPresentException) return UidReadResult.Retryable(ERR_NO_CARD_PRESENT)
                return UidReadResult.Retryable(
                    e.message ?: tr { getString(Res.string.desktop_pcsc_error) }
                )
            }
        lastProbeNotes += "presence=$cardPresent"
        if (!cardPresent) {
            return UidReadResult.Retryable(ERR_NO_CARD_PRESENT)
        }

        var lastSw: Int? = null
        for (protocol in CLASSIC_CONNECT_PROTOCOLS) {
            val card = runCatching { terminal.connect(protocol) }.getOrNull() ?: continue
            try {
                Thread.sleep(CLASSIC_POST_CONNECT_DELAY_MS)
                lastProbeNotes += "connect=$protocol"
                val uidResult = transmitUidApduVariantsClassic(card) ?: continue
                lastSw = uidResult.sw
                if (uidResult.sw != SW_SUCCESS || uidResult.data.isEmpty()) {
                    lastProbeNotes += "sw=${String.format("%04X", uidResult.sw)}"
                    continue
                }
                lastProbeNotes += "getUidOk=${uidResult.data.size}b"
                val uid = uidResult.data.toHexUid()
                val now = System.currentTimeMillis()
                val normalized = uid.uppercase(Locale.US)
                if (normalized == lastDispatchedUid && now - lastDispatchAtMs < UID_REPLAY_GAP_MS) {
                    return UidReadResult.Retryable("Duplicate tap")
                }
                awaitingCardRemoval = true
                awaitingRemovalSinceMs = now
                waitForCardAbsentQuietly(terminal, card)
                lastDispatchedUid = normalized
                lastDispatchAtMs = now
                lastWiredSeenAtMs = now
                return UidReadResult.Success(uid)
            } finally {
                runCatching { card.disconnect(true) }
            }
        }
        return if (lastSw != null) {
            UidReadResult.Retryable("No card detected (SW=${String.format("%04X", lastSw)})")
        } else {
            UidReadResult.Retryable(ERR_NO_CARD_ON_READER)
        }
    }

    /**
     * ACS Get UID for macOS: try every Le variant and keep the longest plausible UID.
     * Le=04 often succeeds first with only 4 bytes of a 7-byte NTAG UID.
     */
    private fun transmitUidApduVariantsClassic(card: Card): ResponseAPDU? {
        val channel = card.basicChannel ?: return null
        val successes = mutableListOf<ResponseAPDU>()
        var lastResponse: ResponseAPDU? = null
        for (apduBytes in CLASSIC_GET_UID_APDU_VARIANTS) {
            val response = runCatching { channel.transmit(CommandAPDU(apduBytes)) }.getOrNull()
                ?: continue
            lastResponse = response
            if (response.sw == SW_SUCCESS && response.data.isNotEmpty()) {
                successes += response
            }
        }
        return preferredUidResponse(successes) ?: lastResponse
    }

    /**
     * SoftDevice BLE: one short DIRECT connect → Escape PN532 first (host RF inventory) →
     * Get UID fallback → disconnect. Skip basicChannel (hangs SoftDevice). Escape calls are
     * time-bounded so a stuck IOCTL cannot stall the poll loop for seconds.
     */
    private fun readUidSoftDeviceFast(live: CardTerminal): UidReadResult {
        lastProbeNotes += "path=ble-fast"
        val card = runCatching { live.connect("direct") }.getOrNull()?.also {
            lastProbeNotes += "softConnect=direct"
        } ?: runCatching { live.connect("EXCLUSIVE;direct") }.getOrNull()?.also {
            lastProbeNotes += "softConnect=EXCLUSIVE;direct"
        } ?: return UidReadResult.Retryable(ERR_BLE_SOFTDEVICE_NO_UID)

        var emptyPoll = false
        try {
            maybeEnsureSoftDevicePolling(card)

            // Prefer PN532 InListPassiveTarget — SoftDevice often never flips card-present / ATR.
            val poll = byteArrayOf(
                0xFF.toByte(), 0x00, 0x00, 0x00, 0x04,
                0xD4.toByte(), 0x4A, 0x01, 0x00
            )
            val polled = transmitEscape(card, poll, softFast = true)
            if (polled != null) {
                parseUidFromPn532InListPassiveTarget(polled)?.let { uid ->
                    lastProbeNotes += "escapePollUid=ok"
                    return acceptUid(live, uid)
                }
            }
            val getUid = GET_UID_APDU_FAST.first()
            val raw = transmitEscape(card, getUid, softFast = true)
            if (raw != null) {
                parseUidFromGetDataEscape(raw)?.let { uid ->
                    lastProbeNotes += "escapeGetUid=ok"
                    return acceptUid(live, uid)
                }
            }
            emptyPoll = true
            return UidReadResult.Retryable(ERR_NO_CARD_ON_READER)
        } finally {
            runCatching { card.disconnect(false) }
            // After disconnect, give SoftDevice RF / Windows stack a beat before next connect.
            if (emptyPoll) {
                Thread.sleep(SOFTDEVICE_INTER_POLL_MS)
            }
        }
    }

    private fun maybeEnsureSoftDevicePolling(card: Card) {
        val now = System.currentTimeMillis()
        if (now - piccPollingEnsuredAtMs < PICC_POLL_RESYNC_MS) return
        val opened = transmitEscape(
            card,
            byteArrayOf(0xE0.toByte(), 0x00, 0x00, 0x40, 0x01),
            softFast = true,
        ) != null
        lastProbeNotes += if (opened) "blePollEscape=ok" else "blePollEscape=skip"
        piccPollingEnsuredAtMs = now
    }

    /**
     * Windows USB: follow the macOS-proven rhythm (patient presence wait → shared APDU →
     * reset disconnect). Keep Escape / PN532 only as fallbacks for ACS stacks that beep
     * without flipping PC/SC card-present.
     */
    private fun readUidWired(live: CardTerminal, cardWaitMs: Long): UidReadResult {
        lastProbeNotes += "path=wired-win"
        val waitMs = maxOf(cardWaitMs, WIRED_CARD_WAIT_MS)
        val cardPresent = waitUntilCardPresent(live, waitMs)
        lastProbeNotes += "presence=$cardPresent"

        if (cardPresent) {
            when (val viaApdu = readUidViaGetDataApdu(live, resetReader = true)) {
                is UidReadResult.Success -> return acceptUid(live, viaApdu.uid)
                is UidReadResult.Fatal -> return viaApdu
                is UidReadResult.Retryable -> lastProbeNotes += "getUid=${viaApdu.error}"
                UidReadResult.NoReader -> lastProbeNotes += "getUid=NoReader"
            }
            // Presence flipped but Get UID failed — enable PICC poll + full Escape recovery.
            maybeEnsureAcsPiccPolling(live)
            return readUidWiredEscapeFallbacks(live, softFast = false)
        }

        // No presence bit: hardware may still have seen a tag (common on Microsoft CCID).
        // One time-bounded Escape inventory — not the full multi-protocol thrash every idle poll.
        maybeEnsureAcsPiccPolling(live)
        return readUidWiredEscapeFallbacks(live, softFast = true).let { fallback ->
            if (fallback is UidReadResult.Retryable &&
                (fallback.error == ERR_NO_CARD_ON_READER ||
                    fallback.error == ERR_ESCAPE_BLOCKED ||
                    fallback.error == ERR_BLE_SOFTDEVICE_NO_UID ||
                    fallback.error?.startsWith("PN532") == true)
            ) {
                UidReadResult.Retryable(ERR_NO_CARD_PRESENT)
            } else {
                fallback
            }
        }
    }

    private fun readUidWiredEscapeFallbacks(
        live: CardTerminal,
        softFast: Boolean,
    ): UidReadResult {
        when (val viaEscape = readUidViaEscapeGetData(live, softFast = softFast)) {
            is UidReadResult.Success -> return acceptUid(live, viaEscape.uid)
            is UidReadResult.Fatal -> return viaEscape
            is UidReadResult.Retryable -> lastProbeNotes += "escapeUid=${viaEscape.error}"
            UidReadResult.NoReader -> lastProbeNotes += "escapeUid=NoReader"
        }
        when (val viaPn532 = readUidViaPn532Poll(live, softFast = softFast)) {
            is UidReadResult.Success -> return acceptUid(live, viaPn532.uid)
            is UidReadResult.Fatal -> return viaPn532
            is UidReadResult.Retryable -> {
                lastProbeNotes += "pn532=${viaPn532.error}"
                return viaPn532
            }
            UidReadResult.NoReader -> return viaPn532
        }
    }

    /**
     * SoftDevice/BLE often never reports card-absent after a tap.
     * Time-debounce only — do not kill the RF field between taps.
     */
    private fun passCardRemovalGate(terminal: CardTerminal): Boolean {
        if (!awaitingCardRemoval) return true
        val bleish = isBleishTerminal(terminal.name.orEmpty())
        val elapsed = System.currentTimeMillis() - awaitingRemovalSinceMs
        // SoftDevice: skip blocking waitForCardAbsent (adds latency and rarely works).
        val stillPresent = if (bleish) {
            false
        } else {
            val removed = runCatching {
                terminal.waitForCardAbsent(CARD_ABSENT_WAIT_MS)
            }.getOrDefault(false)
            if (removed) {
                awaitingCardRemoval = false
                return true
            }
            runCatching { terminal.isCardPresent }.getOrDefault(false)
        }
        when {
            !stillPresent || (bleish && elapsed >= SOFTDEVICE_REARM_MS) -> {
                awaitingCardRemoval = false
                return true
            }
            !bleish && elapsed >= WIRED_REMOVAL_TIMEOUT_MS -> {
                awaitingCardRemoval = false
                return true
            }
            else -> return false
        }
    }

    private fun acceptUid(terminal: CardTerminal, uid: String): UidReadResult {
        val now = System.currentTimeMillis()
        val normalized = uid.uppercase(Locale.US)
        val bleish = isBleishTerminal(terminal.name.orEmpty())
        val replayGap = if (bleish) SOFTDEVICE_REPLAY_GAP_MS else UID_REPLAY_GAP_MS
        if (normalized == lastDispatchedUid && now - lastDispatchAtMs < replayGap) {
            return UidReadResult.Retryable("Duplicate tap")
        }
        lastDispatchedUid = normalized
        lastDispatchAtMs = now
        lastWiredSeenAtMs = now
        awaitingCardRemoval = true
        awaitingRemovalSinceMs = now
        if (!bleish) {
            runCatching { terminal.waitForCardAbsent(CARD_ABSENT_WAIT_MS) }
        }
        // SoftDevice: no antenna off/on — that made the next tap need many beeps.
        return UidReadResult.Success(uid)
    }

    private fun isBleishTerminal(name: String): Boolean {
        // SoftDevice ghost heuristics are Windows-only. On macOS a USB ACR1255U-J1 must use
        // the classic wired path, not SoftDevice Escape.
        if (!isWindowsOs()) {
            return classifyTerminal(name) == ReaderKind.BLE
        }
        return isGhostOrBleSoftDevice(name) || classifyTerminal(name) == ReaderKind.BLE
    }

    private fun maybeEnsureAcsPiccPolling(terminal: CardTerminal) {
        val now = System.currentTimeMillis()
        if (now - piccPollingEnsuredAtMs < PICC_POLL_RESYNC_MS) return
        runCatching { ensureAcsPiccPollingEnabled(terminal) }
        piccPollingEnsuredAtMs = now
    }

    private fun readUidViaGetDataApdu(
        terminal: CardTerminal,
        resetReader: Boolean = false,
    ): UidReadResult {
        var lastSw: Int? = null
        var lastConnectError: String? = null
        var lastProtocol: String? = null
        // Shared protocols first (macOS-style). "direct"/EXCLUSIVE only as last resorts —
        // opening DIRECT on every empty Windows poll made USB flaky.
        for (protocol in WIRED_CONNECT_PROTOCOLS) {
            val card = runCatching { terminal.connect(protocol) }.getOrElse { e ->
                lastConnectError = "$protocol: ${e.message}"
                null
            } ?: continue
            lastProtocol = protocol
            try {
                Thread.sleep(CLASSIC_POST_CONNECT_DELAY_MS)
                val protocolName = runCatching { card.protocol }.getOrNull()
                lastProbeNotes += "connect=$protocol${protocolName?.let { "/$it" } ?: ""}"
                val uidResult = transmitUidApduVariants(card) ?: continue
                lastSw = uidResult.sw
                if (uidResult.sw != SW_SUCCESS || uidResult.data.isEmpty()) {
                    lastProbeNotes += "sw=${String.format("%04X", uidResult.sw)}"
                    continue
                }
                lastProbeNotes += "getUidOk=${uidResult.data.size}b"
                if (resetReader) {
                    waitForCardAbsentQuietly(terminal, card)
                }
                return UidReadResult.Success(uidResult.data.toHexUid())
            } finally {
                // Reset (true) rearms ACS USB PICC like the macOS classic path.
                runCatching { card.disconnect(resetReader) }
            }
        }
        return when {
            lastSw != null ->
                UidReadResult.Retryable(
                    "No card detected (SW=${String.format("%04X", lastSw)}; protocol=$lastProtocol)"
                )
            lastConnectError != null ->
                UidReadResult.Retryable("Connect failed: $lastConnectError")
            else ->
                UidReadResult.Retryable(ERR_NO_CARD_ON_READER)
        }
    }

    /**
     * PN532 InListPassiveTarget via ACR Direct Transmit / Escape.
     * Needed when the reader beeps but PC/SC never reports a card present.
     */
    private fun readUidViaPn532Poll(terminal: CardTerminal, softFast: Boolean = false): UidReadResult {
        var lastConnectError: String? = null
        var escapeAttempted = false
        var escapeOk = false
        var channelAttempted = false
        val protocols = if (softFast) SOFTDEVICE_CONNECT_PROTOCOLS else PN532_CONNECT_PROTOCOLS
        val delayMs = if (softFast) SOFTDEVICE_POST_CONNECT_MS else POST_CONNECT_DELAY_MS
        for (protocol in protocols) {
            val card = runCatching { terminal.connect(protocol) }.getOrElse { e ->
                lastConnectError = "$protocol: ${e.message}"
                null
            } ?: continue
            try {
                Thread.sleep(delayMs)
                val outcome = transmitPn532InListPassiveTargetDetailed(card, softFast)
                channelAttempted = channelAttempted || outcome.channelAttempted
                escapeAttempted = escapeAttempted || outcome.escapeAttempted
                escapeOk = escapeOk || outcome.escapeOk
                val uid = outcome.payload?.let { parseUidFromPn532InListPassiveTarget(it) }
                if (uid != null) {
                    lastProbeNotes += "pn532Protocol=$protocol;escapeOk=$escapeOk"
                    return UidReadResult.Success(uid)
                }
            } finally {
                runCatching { card.disconnect(false) }
            }
        }
        return when {
            escapeAttempted && !escapeOk ->
                UidReadResult.Retryable(ERR_ESCAPE_BLOCKED)
            lastConnectError != null ->
                UidReadResult.Retryable("PN532 connect failed: $lastConnectError")
            channelAttempted ->
                UidReadResult.Retryable(ERR_NO_CARD_ON_READER)
            else ->
                UidReadResult.Retryable(ERR_NO_CARD_ON_READER)
        }
    }

    private data class Pn532TransmitOutcome(
        val payload: ByteArray?,
        val channelAttempted: Boolean,
        val escapeAttempted: Boolean,
        val escapeOk: Boolean,
    )

    private fun transmitPn532InListPassiveTargetDetailed(
        card: Card,
        softFast: Boolean = false,
    ): Pn532TransmitOutcome {
        val directApdu = byteArrayOf(
            0xFF.toByte(), 0x00, 0x00, 0x00, 0x04,
            0xD4.toByte(), 0x4A, 0x01, 0x00
        )
        val directApduLe = byteArrayOf(
            0xFF.toByte(), 0x00, 0x00, 0x00, 0x04,
            0xD4.toByte(), 0x4A, 0x01, 0x00, 0x00
        )
        var channelAttempted = false
        var escapeAttempted = false
        var escapeOk = false
        val channel = runCatching { card.basicChannel }.getOrNull()
        if (channel != null) {
            channelAttempted = true
            for (apdu in arrayOf(directApdu, directApduLe)) {
                val response = runCatching { channel.transmit(CommandAPDU(apdu)) }.getOrNull()
                    ?: continue
                val data = when {
                    response.sw == SW_SUCCESS && response.data.isNotEmpty() -> response.data
                    response.bytes.isNotEmpty() -> response.bytes
                    else -> continue
                }
                if (data.isNotEmpty()) {
                    return Pn532TransmitOutcome(data, channelAttempted, escapeAttempted, escapeOk)
                }
            }
        }
        for (apdu in arrayOf(directApdu, if (softFast) null else directApduLe).filterNotNull()) {
            escapeAttempted = true
            val escaped = transmitEscape(card, apdu, softFast) ?: continue
            escapeOk = true
            return Pn532TransmitOutcome(escaped, channelAttempted, escapeAttempted, escapeOk)
        }
        return Pn532TransmitOutcome(null, channelAttempted, escapeAttempted, escapeOk)
    }

    private fun transmitPn532InListPassiveTarget(card: Card): ByteArray? =
        transmitPn532InListPassiveTargetDetailed(card).payload

    private fun parseUidFromPn532InListPassiveTarget(raw: ByteArray): String? {
        val data = raw
        val start = data.indices.firstOrNull { (data[it].toInt() and 0xFF) == 0xD5 } ?: return null
        if (start + 2 >= data.size) return null
        if ((data[start + 1].toInt() and 0xFF) != 0x4B) return null
        val nbTg = data[start + 2].toInt() and 0xFF
        if (nbTg < 1) return null
        var idx = start + 3
        if (idx >= data.size) return null
        idx += 1 // Tg
        if (idx + 3 >= data.size) return null
        idx += 2 // SENS_RES / ATQA
        idx += 1 // SEL_RES / SAK
        if (idx >= data.size) return null
        val uidLen = data[idx].toInt() and 0xFF
        idx += 1
        if (uidLen !in 4..10) return null
        if (idx + uidLen > data.size) return null
        return data.copyOfRange(idx, idx + uidLen).toHexUid()
    }

    private fun resolveLiveTerminal(terminal: CardTerminal): CardTerminal? {
        val name = terminal.name ?: return null
        listTerminals().firstOrNull { it.name == name }?.let { return it }
        refreshPcscListing()
        val refreshed = listTerminals()
        refreshed.firstOrNull { it.name == name }?.let { return it }
        if (!isWindowsOs()) {
            return refreshed.firstOrNull {
                classifyTerminal(it.name.orEmpty()) == ReaderKind.USB
            } ?: refreshed.firstOrNull()
        }
        preferredWiredTerminal()?.terminal?.let { return it }
        return refreshed.firstOrNull {
            isAcsFamilyName(it.name) && !isContactInterfaceOnly(it.name)
        }
    }

    /**
     * [CardTerminal.waitForCardPresent] alone is unreliable on some Windows ACS/CCID stacks
     * (hardware beeps while PC/SC status never flips). Poll [CardTerminal.isCardPresent] as well.
     */
    private fun waitUntilCardPresent(terminal: CardTerminal, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(50L)
        while (System.currentTimeMillis() < deadline) {
            val present = runCatching { terminal.isCardPresent }.getOrDefault(false)
            if (present) return true
            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
            if (remaining == 0L) break
            val waited = runCatching {
                terminal.waitForCardPresent(minOf(CARD_PRESENT_POLL_MS, remaining))
            }.getOrDefault(false)
            if (waited || runCatching { terminal.isCardPresent }.getOrDefault(false)) return true
        }
        return runCatching { terminal.isCardPresent }.getOrDefault(false)
    }

    /**
     * ACS Get UID pseudo-APDU. Try every Le variant and keep the longest plausible UID
     * (NTAG cards are 7 bytes; Le=04 often returns only the first 4).
     */
    private fun transmitUidApduVariants(card: Card): ResponseAPDU? {
        val channel = card.basicChannel ?: return null
        val successes = mutableListOf<ResponseAPDU>()
        var lastResponse: ResponseAPDU? = null
        for (apduBytes in WIRED_GET_UID_APDU_VARIANTS) {
            val response = runCatching { channel.transmit(CommandAPDU(apduBytes)) }.getOrNull()
                ?: continue
            lastResponse = response
            if (response.sw == SW_SUCCESS && response.data.isNotEmpty()) {
                successes += response
            }
        }
        // Also try the 4-arg constructor (Ne=0 → 256) which some stacks handle differently.
        val viaCtor = runCatching {
            channel.transmit(CommandAPDU(0xFF, 0xCA, 0x00, 0x00, 256))
        }.getOrNull()
        if (viaCtor != null) {
            lastResponse = viaCtor
            if (viaCtor.sw == SW_SUCCESS && viaCtor.data.isNotEmpty()) {
                successes += viaCtor
            }
        }
        return preferredUidResponse(successes) ?: lastResponse
    }

    private fun preferredUidResponse(responses: List<ResponseAPDU>): ResponseAPDU? {
        val plausible = responses.filter { it.data.size == 4 || it.data.size == 7 || it.data.size == 10 }
        return (plausible.ifEmpty { responses.filter { it.data.size in 4..10 } })
            .maxByOrNull { it.data.size }
    }

    private fun preferredUidBytes(candidates: List<ByteArray>): ByteArray? {
        val plausible = candidates.filter { it.size == 4 || it.size == 7 || it.size == 10 }
        return (plausible.ifEmpty { candidates.filter { it.size in 4..10 } })
            .maxByOrNull { it.size }
    }

    private fun waitForCardAbsentQuietly(terminal: CardTerminal, card: Card) {
        runCatching {
            terminal.waitForCardAbsent(CARD_ABSENT_WAIT_MS)
        }
        runCatching {
            if (terminal.isCardPresent) {
                card.disconnect(false)
            }
        }
    }

    private fun classifyTerminal(name: String): ReaderKind {
        val lower = name.lowercase(Locale.US)
        // macOS: restore the simple classifier that worked with CryptoTokenKit names.
        // SoftDevice / ICC heuristics are Windows-only — they hide real USB readers on Mac.
        if (!isWindowsOs()) {
            return when {
                lower.contains("bluetooth") || lower.contains(" ble") -> ReaderKind.BLE
                lower.contains("acr122") || lower.contains("usb") -> ReaderKind.USB
                lower.contains("acr125") || lower.contains("1255") -> ReaderKind.USB
                lower.contains("acs") && lower.contains("nfc") -> ReaderKind.USB
                else -> ReaderKind.OTHER
            }
        }
        return when {
            isGhostOrBleSoftDevice(name) -> ReaderKind.BLE
            lower.contains("bluetooth") || lower.contains(" ble") ||
                lower.contains("v2 ble") || lower.contains(" ble ") -> ReaderKind.BLE
            lower.contains("acr122") || lower.contains("usb") -> ReaderKind.USB
            // Explicit PICC / contactless USB naming from ACS PC/SC
            lower.contains("picc") || lower.contains("contactless") -> ReaderKind.USB
            lower.contains("acr125") || lower.contains("1255") -> ReaderKind.USB
            lower.contains("acs") && (lower.contains("nfc") || lower.contains("acr")) -> ReaderKind.USB
            lower.contains("ccid") -> ReaderKind.USB
            else -> ReaderKind.OTHER
        }
    }

    /**
     * ACS Bluetooth Device Management Tool / SoftDevice publishes readers like
     * `ACS ACR1255U-J1-042283 0` even with no USB cable — must not count as USB PICC.
     * Windows-only: on macOS the same model over USB must stay classified as wired USB.
     */
    private fun isGhostOrBleSoftDevice(name: String): Boolean =
        isWindowsOs() && isAcsBleSoftDeviceName(name)

    /**
     * SoftDevice often only accepts DIRECT and never flips isCardPresent. Send Get Data as Escape.
     */
    private fun readUidViaEscapeGetData(
        terminal: CardTerminal,
        softFast: Boolean = false,
    ): UidReadResult {
        var escapeOk = false
        val protocols = if (softFast) SOFTDEVICE_CONNECT_PROTOCOLS else PN532_CONNECT_PROTOCOLS
        val delayMs = if (softFast) SOFTDEVICE_POST_CONNECT_MS else POST_CONNECT_DELAY_MS
        val uidApdus = if (softFast) GET_UID_APDU_FAST else WIRED_GET_UID_APDU_VARIANTS
        for (protocol in protocols) {
            val card = runCatching { terminal.connect(protocol) }.getOrNull() ?: continue
            try {
                Thread.sleep(delayMs)
                // Prefer basic channel when available (fast).
                val channel = runCatching { card.basicChannel }.getOrNull()
                if (channel != null) {
                    val successes = mutableListOf<ByteArray>()
                    for (apdu in uidApdus) {
                        val response = runCatching {
                            channel.transmit(CommandAPDU(apdu))
                        }.getOrNull() ?: continue
                        if (response.sw == SW_SUCCESS && response.data.size in 4..10) {
                            successes += response.data
                        }
                    }
                    preferredUidBytes(successes)?.let { uidBytes ->
                        lastProbeNotes += "escapeGetUidChannel=$protocol"
                        return UidReadResult.Success(uidBytes.toHexUid())
                    }
                }
                val escapeUids = mutableListOf<String>()
                for (apdu in uidApdus) {
                    val raw = transmitEscape(card, apdu, softFast) ?: continue
                    escapeOk = true
                    parseUidFromGetDataEscape(raw)?.let { escapeUids += it }
                }
                escapeUids.maxByOrNull { it.length }?.let { uid ->
                    lastProbeNotes += "escapeGetUid=$protocol"
                    return UidReadResult.Success(uid)
                }
                if (!softFast) {
                    val poll = byteArrayOf(
                        0xFF.toByte(), 0x00, 0x00, 0x00, 0x04,
                        0xD4.toByte(), 0x4A, 0x01, 0x00
                    )
                    val polled = transmitEscape(card, poll, softFast = false)
                    if (polled != null) {
                        escapeOk = true
                        parseUidFromPn532InListPassiveTarget(polled)?.let { uid ->
                            lastProbeNotes += "escapePollUid=$protocol"
                            return UidReadResult.Success(uid)
                        }
                    }
                }
            } finally {
                runCatching { card.disconnect(false) }
            }
        }
        return if (escapeOk) {
            UidReadResult.Retryable(ERR_NO_CARD_ON_READER)
        } else {
            UidReadResult.Retryable(ERR_ESCAPE_BLOCKED)
        }
    }

    private fun parseUidFromGetDataEscape(raw: ByteArray): String? {
        if (raw.size >= 6) {
            val sw = ((raw[raw.size - 2].toInt() and 0xFF) shl 8) or (raw[raw.size - 1].toInt() and 0xFF)
            if (sw == SW_SUCCESS) {
                val data = raw.copyOfRange(0, raw.size - 2)
                if (data.size in 4..10) return data.toHexUid()
            }
        }
        if (raw.size in 4..10) return raw.toHexUid()
        val start = raw.indices.firstOrNull {
            it + 1 < raw.size &&
                (raw[it].toInt() and 0xFF) == 0x90 &&
                (raw[it + 1].toInt() and 0xFF) == 0x00
        } ?: return null
        if (start >= 4) {
            return raw.copyOfRange(0, start).toHexUid().takeIf { it.length in 8..20 }
        }
        return null
    }

    private fun transmitEscape(
        card: Card,
        payload: ByteArray,
        softFast: Boolean = false,
    ): ByteArray? {
        val ioctls = if (softFast) intArrayOf(IOCTL_CCID_ESCAPE) else ESCAPE_IOCTLS
        for (ioctl in ioctls) {
            val escaped = if (softFast) {
                transmitEscapeTimed(card, ioctl, payload)
            } else {
                runCatching { card.transmitControlCommand(ioctl, payload) }.getOrNull()
            }
            if (escaped != null && escaped.isNotEmpty()) return escaped
        }
        return null
    }

    private fun transmitEscapeTimed(card: Card, ioctl: Int, payload: ByteArray): ByteArray? {
        val future = softDeviceIo.submit<ByteArray?> {
            runCatching { card.transmitControlCommand(ioctl, payload) }.getOrNull()
        }
        return try {
            future.get(SOFTDEVICE_ESCAPE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            lastProbeNotes += "escapeTimeout=${SOFTDEVICE_ESCAPE_TIMEOUT_MS}ms"
            null
        } catch (_: Exception) {
            future.cancel(true)
            null
        }
    }

    /**
     * ACR1255: enable Automatic PICC Polling via Escape so PC/SC sees card present / ATR.
     * USB PC-linked: E0 00 00 23 01 8F (default 0x8F).
     * Bluetooth SoftDevice: E0 00 00 40 01 (open automatic polling).
     */
    private fun ensureAcsPiccPollingEnabled(terminal: CardTerminal) {
        if (!nameLooksLikeAcr1255(terminal.name.orEmpty())) return
        val bleSoft = isGhostOrBleSoftDevice(terminal.name.orEmpty())
        val commands = if (bleSoft) {
            arrayOf(
                byteArrayOf(0xE0.toByte(), 0x00, 0x00, 0x40, 0x01), // BLE open auto poll
                byteArrayOf(0xE0.toByte(), 0x00, 0x00, 0x23, 0x01, 0x8F.toByte()),
            )
        } else {
            arrayOf(
                byteArrayOf(0xE0.toByte(), 0x00, 0x00, 0x23, 0x01, 0x8F.toByte()),
                byteArrayOf(0xE0.toByte(), 0x00, 0x00, 0x40, 0x01),
            )
        }
        for (protocol in arrayOf("direct", "EXCLUSIVE;direct", "*")) {
            val card = runCatching { terminal.connect(protocol) }.getOrNull() ?: continue
            try {
                for (cmd in commands) {
                    if (transmitEscape(card, cmd) != null) {
                        lastProbeNotes += if (bleSoft) "blePollEscape=ok" else "piccPollEscape=ok"
                        return
                    }
                    val wrapped = byteArrayOf(
                        0xFF.toByte(), 0x00, 0x00, 0x00, cmd.size.toByte()
                    ) + cmd
                    val channel = runCatching { card.basicChannel }.getOrNull()
                    if (channel != null) {
                        val ok = runCatching {
                            channel.transmit(CommandAPDU(wrapped))
                        }.getOrNull()
                        if (ok != null && (ok.sw == SW_SUCCESS || ok.bytes.isNotEmpty())) {
                            lastProbeNotes += if (bleSoft) "blePollApdu=ok" else "piccPollApdu=ok"
                            return
                        }
                    }
                }
            } finally {
                runCatching { card.disconnect(false) }
            }
        }
        lastProbeNotes += if (bleSoft) "blePollEscape=skip" else "piccPollEscape=skip"
    }

    private fun ByteArray.toHexUid(): String = joinToString(separator = "") { "%02X".format(it) }

    /**
     * Process-wide Winscard handle. Creating a new [TerminalFactory] per poll churns SCARDCONTEXT
     * and races the Windows Smart Card service on hot-plug.
     */
    private object PcscContext {
        private val lock = Any()
        private val factoryRef = AtomicReference<TerminalFactory?>(null)
        private val terminalsRef = AtomicReference<CardTerminals?>(null)
        @Volatile private var providerOk = false
        @Volatile var lastInitError: String? = null
            private set

        data class ListOutcome(val terminals: List<CardTerminal>, val error: String?)

        fun isAvailable(): Boolean {
            ensureProvider()
            return providerOk
        }

        fun listTerminals(): ListOutcome = synchronized(lock) {
            ensureProviderLocked()
            if (!providerOk) {
                val detail = lastInitError?.takeIf { it.isNotBlank() }
                return ListOutcome(
                    emptyList(),
                    if (detail != null) "PC/SC provider unavailable: $detail"
                    else "PC/SC provider unavailable"
                )
            }
            return try {
                val terminals = terminalsRef.get() ?: openTerminalsLocked()
                val listed = terminals.list()
                ListOutcome(listed, null)
            } catch (e: Exception) {
                // Context lost (service restarted) — rebuild and surface the error.
                closeTerminalsLocked()
                factoryRef.set(null)
                ListOutcome(emptyList(), e.message ?: e.javaClass.simpleName)
            }
        }

        fun refresh() = synchronized(lock) {
            closeTerminalsLocked()
            if (providerOk) {
                runCatching { openTerminalsLocked() }
            }
        }

        fun reset() = synchronized(lock) {
            closeTerminalsLocked()
            factoryRef.set(null)
            // Keep providerOk — re-open on next list.
        }

        private fun ensureProvider() {
            if (providerOk && factoryRef.get() != null) return
            synchronized(lock) { ensureProviderLocked() }
        }

        private fun ensureProviderLocked() {
            if (providerOk && factoryRef.get() != null) return
            try {
                if (Security.getProvider(Smartcardio.PROVIDER_NAME) == null) {
                    Security.insertProviderAt(Smartcardio(), 1)
                }
                val factory = TerminalFactory.getInstance("PC/SC", null, Smartcardio.PROVIDER_NAME)
                factoryRef.set(factory)
                openTerminalsLocked()
                providerOk = true
                lastInitError = null
            } catch (e: Throwable) {
                providerOk = false
                factoryRef.set(null)
                terminalsRef.set(null)
                lastInitError = e.message ?: e.javaClass.simpleName
                AppLogger.e(LOG_TAG, "PC/SC provider init failed", e)
            }
        }

        private fun openTerminalsLocked(): CardTerminals {
            val factory = factoryRef.get()
                ?: TerminalFactory.getInstance("PC/SC", null, Smartcardio.PROVIDER_NAME).also {
                    factoryRef.set(it)
                }
            // terminals() re-establishes SCARDCONTEXT (jnasmartcardio) — critical after service restart.
            val terminals = factory.terminals()
            terminalsRef.set(terminals)
            return terminals
        }

        private fun closeTerminalsLocked() {
            val terminals = terminalsRef.getAndSet(null) ?: return
            // jnasmartcardio CardTerminals implements close() but the javax API does not declare it.
            runCatching {
                val close = terminals.javaClass.methods.firstOrNull {
                    it.name == "close" && it.parameterCount == 0
                }
                close?.invoke(terminals)
            }
        }
    }

    companion object {
        private const val LOG_TAG = "DesktopPcscCardReader"
        private const val SW_SUCCESS = 0x9000
        private const val PCSC_ID_PREFIX = "pcsc:"
        private const val UID_REPLAY_GAP_MS = 700L
        private const val SOFTDEVICE_REPLAY_GAP_MS = 550L
        private const val CARD_ABSENT_WAIT_MS = 400L
        private const val CARD_WAIT_MS = 500L
        /** Pre-Windows-rewrite USB poll window (macOS classic path). */
        private const val CLASSIC_CARD_WAIT_MS = 3_000L
        /** Windows USB presence wait — same patient rhythm as macOS classic. */
        private const val WIRED_CARD_WAIT_MS = 2_500L
        private const val CARD_PRESENT_POLL_MS = 80L
        private const val DIAGNOSTIC_CARD_WAIT_MS = 12_000L
        private const val DIAGNOSTIC_POLL_MS = 600L
        private const val POST_CONNECT_DELAY_MS = 80L
        private const val CLASSIC_POST_CONNECT_DELAY_MS = 120L
        private const val SOFTDEVICE_POST_CONNECT_MS = 15L
        /** Pause after an empty SoftDevice poll (outside connect) to avoid thrashing BT/PC/SC. */
        private const val SOFTDEVICE_INTER_POLL_MS = 70L
        /** SoftDevice Escape IOCTL budget — hung Winscard was a common multi-second stall. */
        private const val SOFTDEVICE_ESCAPE_TIMEOUT_MS = 350L
        /** SoftDevice debounce between accepted UIDs (no RF field kill). */
        private const val SOFTDEVICE_REARM_MS = 350L
        /** Wired fallback if isCardPresent stays stuck true. */
        private const val WIRED_REMOVAL_TIMEOUT_MS = 3_500L
        /** Don't spam Escape "enable poll" on every tick. */
        private const val PICC_POLL_RESYNC_MS = 30_000L
        /** Keep UI “reader connected” for a few seconds across empty PC/SC list blips. */
        private const val WIRED_STICKY_MS = 2_500L

        /** Windows SCARD_CTL_CODE variants used by ACS / WUDF SoftDevice. */
        private val ESCAPE_IOCTLS = intArrayOf(
            0x003136B0, // SCARD_CTL_CODE(3500) — ACS CCID Escape
            0x00310004, // SCARD_CTL_CODE(1)
        )

        /** Windows SCARD_CTL_CODE(3500) — ACS CCID Escape / Direct Transmit. */
        private const val IOCTL_CCID_ESCAPE = 0x003136B0

        /** Internal machine-readable status codes (not shown as final UX copy). */
        private const val ERR_NO_CARD_PRESENT = "No card present"
        private const val ERR_NO_CARD_ON_READER = "No card detected on reader"
        private const val ERR_WAITING_REMOVAL = "Waiting for card removal"
        private const val ERR_ESCAPE_BLOCKED =
            "CCID Escape failed — with Microsoft CCID enable EscapeCommandEnable; with ACS driver, hold a card and retry PICC Get UID"
        private const val ERR_BLE_SOFTDEVICE_NO_UID =
            "BLE SoftDevice beeps but PC/SC got no UID — wake reader, keep ACS BT link Connected, hold card during Test"

        /**
         * USB shared connects (macOS rhythm without T=CL — rejected by jnasmartcardio on Windows).
         * Prefer these over DIRECT/EXCLUSIVE so USB PICC is not contended every poll.
         */
        private val WIRED_CONNECT_PROTOCOLS = arrayOf(
            "*",
            "T=1",
            "T=0",
            "direct",
        )

        /**
         * Contactless first — required by many ACS drivers / macOS CryptoTokenKit for PICC
         * pseudo-APDUs. (jnasmartcardio on Windows rejects T=CL; classic path is non-Windows.)
         */
        private val CLASSIC_CONNECT_PROTOCOLS = arrayOf("T=CL", "*", "T=1", "T=0")

        private val PN532_CONNECT_PROTOCOLS = arrayOf(
            "direct",
            "EXCLUSIVE;direct",
            "*",
        )

        /** SoftDevice: DIRECT first; EXCLUSIVE only as fallback if DIRECT connect fails. */
        private val SOFTDEVICE_CONNECT_PROTOCOLS = arrayOf(
            "direct",
            "EXCLUSIVE;direct",
        )

        /**
         * Le=04 first (macOS-proven), then Le=00 / other lengths for Microsoft CCID / ACS.
         */
        private val WIRED_GET_UID_APDU_VARIANTS = arrayOf(
            byteArrayOf(0xFF.toByte(), 0xCA.toByte(), 0x00, 0x00, 0x04),
            byteArrayOf(0xFF.toByte(), 0xCA.toByte(), 0x00, 0x00, 0x00),
            byteArrayOf(0xFF.toByte(), 0xCA.toByte(), 0x00, 0x01, 0x00),
            byteArrayOf(0xFF.toByte(), 0xCA.toByte(), 0x00, 0x00, 0x08),
            byteArrayOf(0xFF.toByte(), 0xCA.toByte(), 0x00, 0x00, 0x10),
        )

        /** Le=04 first — macOS CryptoTokenKit often needs this (Le=00 → SW=6300). */
        private val CLASSIC_GET_UID_APDU_VARIANTS = arrayOf(
            byteArrayOf(0xFF.toByte(), 0xCA.toByte(), 0x00, 0x00, 0x04),
            byteArrayOf(0xFF.toByte(), 0xCA.toByte(), 0x00, 0x00, 0x00),
            byteArrayOf(0xFF.toByte(), 0xCA.toByte(), 0x00, 0x00, 0x08),
            byteArrayOf(0xFF.toByte(), 0xCA.toByte(), 0x00, 0x00, 0x10),
        )

        private val GET_UID_APDU_FAST = arrayOf(
            byteArrayOf(0xFF.toByte(), 0xCA.toByte(), 0x00, 0x00, 0x00),
            byteArrayOf(0xFF.toByte(), 0xCA.toByte(), 0x00, 0x00, 0x04),
        )

        fun terminalId(terminalName: String): String = "$PCSC_ID_PREFIX$terminalName"

        fun isPcscTerminalId(id: String): Boolean =
            id.startsWith(PCSC_ID_PREFIX, ignoreCase = true)

        fun isPreferredContactless(name: String): Boolean {
            val lower = name.lowercase(Locale.US)
            return lower.contains("picc") || lower.contains("contactless")
        }

        /**
         * SoftDevice / BLE management-tool names look like USB in PC/SC but are not a USB PICC.
         * Example: `ACS ACR1255U-J1-042283 0`
         */
        fun isAcsBleSoftDeviceName(name: String): Boolean {
            val n = name.lowercase(Locale.US).trim()
            if (n.contains("bluetooth") || n.contains(" ble") || n.contains("v2 ble")) return true
            // Model-serial SoftDevice without "PICC Reader"
            if (Regex("""acr1255u-j1-\d+""").containsMatchIn(n) && !n.contains("picc")) return true
            if (Regex("""acr1255[^\s]*-\d{4,}""").containsMatchIn(n) &&
                !n.contains("picc reader")
            ) {
                return true
            }
            return false
        }

        fun isContactInterfaceOnly(name: String): Boolean {
            val lower = name.lowercase(Locale.US)
            if (isPreferredContactless(lower)) return false
            return lower.contains("icc") ||
                lower.contains(" sam") ||
                lower.endsWith("sam") ||
                lower.contains("(sam)") ||
                lower.contains("-sam")
        }

        fun isAcsFamilyName(name: String): Boolean {
            val lower = name.lowercase(Locale.US)
            return lower.contains("acr122") ||
                lower.contains("acr125") ||
                lower.contains("1255") ||
                (lower.contains("acs") && (lower.contains("nfc") || lower.contains("acr")))
        }

        fun nameLooksLikeAcr1255(name: String): Boolean {
            val n = name.lowercase(Locale.US)
            return n.contains("acr1255") ||
                n.contains("1255u-j1") ||
                n.contains("1255u") ||
                (n.contains("acr") && n.contains("1255"))
        }

        fun isWindowsOs(): Boolean =
            System.getProperty("os.name").orEmpty().lowercase(Locale.US).contains("win")
    }
}
