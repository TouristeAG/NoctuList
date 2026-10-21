package com.eventmanager.app.ui.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinuxUiScaleTest {
    @Test
    fun waylandUsesIntegerGdkScaleAndIgnoresFractionalDpiScale() {
        val scale = resolveLinuxUiScale(
            LinuxScaleSignals(
                wayland = true,
                gdkScale = 2.0,
                gdkDpiScale = 0.75,
                gnomeMonitorScale = 1.5,
            ),
        )
        assertEquals(2.0, scale)
    }

    @Test
    fun integerGdkScaleOfOneDoesNotHideTheMonitorScale() {
        val scale = resolveLinuxUiScale(
            LinuxScaleSignals(
                wayland = true,
                gdkScale = 1.0,
                gdkDpiScale = 0.75,
                gnomeMonitorScale = 2.0,
            ),
        )
        assertEquals(2.0, scale)
    }

    @Test
    fun x11MultipliesGdkScaleByDpiScale() {
        val scale = resolveLinuxUiScale(
            LinuxScaleSignals(
                wayland = false,
                gdkScale = 2.0,
                gdkDpiScale = 0.75,
            ),
        )
        assertEquals(1.5, scale)
    }

    @Test
    fun gnomePrimaryMonitorBeatsASecondaryScale() {
        val xml = """
            <monitors version="2">
              <configuration>
                <logicalmonitor>
                  <scale>2</scale>
                  <primary>yes</primary>
                </logicalmonitor>
                <logicalmonitor>
                  <scale>1</scale>
                </logicalmonitor>
              </configuration>
              <configuration>
                <logicalmonitor>
                  <scale>1</scale>
                  <primary>yes</primary>
                </logicalmonitor>
              </configuration>
            </monitors>
        """.trimIndent()
        assertEquals(2.0, gnomePrimaryScale(xml))
        assertEquals(2.0, resolveLinuxUiScale(LinuxScaleSignals(gnomeMonitorScale = gnomePrimaryScale(xml))))
    }

    @Test
    fun fractionalGnomeScaleIsKept() {
        val xml = """
            <configuration>
              <logicalmonitor>
                <scale>1.25</scale>
                <primary>yes</primary>
              </logicalmonitor>
            </configuration>
        """.trimIndent()
        assertEquals(1.25, gnomePrimaryScale(xml))
    }

    @Test
    fun kdeXwaylandScaleIsUsedWhenGnomeHasNone() {
        val kwin = """
            [Xwayland]
            Scale=1.5
        """.trimIndent()
        assertEquals(1.5, kdeUiScale(kwin, ""))
        assertEquals(1.5, resolveLinuxUiScale(LinuxScaleSignals(kdeScale = 1.5)))
    }

    @Test
    fun defaultXftDpiDoesNotForceAScale() {
        assertNull(resolveLinuxUiScale(LinuxScaleSignals(xftDpi = 96.0)))
        assertEquals(2.0, resolveLinuxUiScale(LinuxScaleSignals(xftDpi = 192.0)))
    }

    @Test
    fun gnomeSettingsZeroMeansUnset() {
        assertEquals(0, parseGnomeSettingsScale("uint32 0"))
        assertNull(resolveLinuxUiScale(LinuxScaleSignals(gnomeSettingsScale = 0, xftDpi = 96.0)))
        assertEquals(2.0, resolveLinuxUiScale(LinuxScaleSignals(gnomeSettingsScale = 2)))
    }

    @Test
    fun parsesXftDpiLine() {
        assertEquals(192.0, parseXftDpi("Xft.dpi:\t192\nXft.antialias:\t1\n"))
        assertNull(parseXftDpi("Xft.antialias:\t1\n"))
    }

    @Test
    fun rejectsTinyOrHugeScales() {
        assertNull(resolveLinuxUiScale(LinuxScaleSignals(gnomeMonitorScale = 0.5)))
        assertNull(resolveLinuxUiScale(LinuxScaleSignals(xftDpi = 0.0)))
        assertEquals("2", formatUiScale(2.0))
        assertEquals("1.25", formatUiScale(1.25))
    }
}
