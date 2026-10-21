package com.eventmanager.app.platform.hardware

import java.awt.Dimension
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopWebcamSupportTest {
    @Test
    fun linuxPrefers720pOver1080p() {
        val choice = chooseScanResolution(
            listOf(Dimension(1920, 1080), Dimension(1280, 720), Dimension(640, 480)),
            "Linux",
        )
        assertEquals(1280, choice.size.width)
        assertEquals(720, choice.size.height)
        assertTrue(choice is ScanResolutionChoice.Advertised)
    }

    @Test
    fun linuxSkipsUncompressed1080WhenVgaIsAdvertised() {
        val size = chooseLinuxScanResolution(listOf(Dimension(1920, 1080), Dimension(640, 480)))
        assertEquals(640, size.width)
        assertEquals(480, size.height)
    }

    @Test
    fun linuxUsesSmallestModeWhenEveryModeIsAbove720p() {
        val size = chooseLinuxScanResolution(listOf(Dimension(3840, 2160), Dimension(1920, 1080)))
        assertEquals(1920, size.width)
        assertEquals(1080, size.height)
    }

    @Test
    fun linuxDoesNotInventAModeWhenNothingIsAdvertised() {
        val size = chooseLinuxScanResolution(emptyList())
        assertEquals(640, size.width)
        assertEquals(480, size.height)
    }

    @Test
    fun otherPlatformsCanRequestUnlisted720p() {
        val choice = chooseScanResolution(listOf(Dimension(320, 240)), "Mac OS X")
        assertTrue(choice is ScanResolutionChoice.Custom)
        assertEquals(1280, choice.size.width)
        assertEquals(720, choice.size.height)
    }

    @Test
    fun otherPlatformsKeepAdvertised1080p() {
        val choice = chooseScanResolution(listOf(Dimension(1920, 1080)), "Windows 11")
        assertTrue(choice is ScanResolutionChoice.Advertised)
        assertEquals(1920, choice.size.width)
    }

    @Test
    fun findsUsbPowerControlNextToTheVideoNode() {
        val root = Files.createTempDirectory("video4linux").toFile()
        val video = File(root, "video0").apply { mkdirs() }
        val control = File(video, "device/power/control").apply {
            parentFile.mkdirs()
            writeText("auto")
        }
        assertEquals(control.canonicalFile, webcamPowerControlFile(video)?.canonicalFile)
    }

    @Test
    fun stalledFramesRequestAReopenThenStop() {
        val tracker = WebcamStallTracker(thresholdNs = 1_000, maxReopens = 2)
        assertEquals(false, tracker.onFrame(timestamp = 10, nowNs = 0))
        assertEquals(false, tracker.onFrame(timestamp = 10, nowNs = 0))
        assertEquals(false, tracker.onFrame(timestamp = 10, nowNs = 999))
        assertEquals(true, tracker.onFrame(timestamp = 10, nowNs = 1_000))
        assertEquals(false, tracker.onFrame(timestamp = 10, nowNs = 1_500))
        assertEquals(true, tracker.onFrame(timestamp = 10, nowNs = 2_000))
        assertEquals(false, tracker.onFrame(timestamp = 10, nowNs = 4_000))
        assertEquals(2, tracker.reopens)
    }

    @Test
    fun trackerGivesUpWhenTheSameFrameSurvivesEveryReopen() {
        val tracker = WebcamStallTracker(thresholdNs = 10, maxReopens = 1)
        assertEquals(false, tracker.onFrame(timestamp = 5, nowNs = 0))
        assertEquals(false, tracker.onFrame(timestamp = 5, nowNs = 0))
        assertEquals(true, tracker.onFrame(timestamp = 5, nowNs = 10))
        assertEquals(false, tracker.onFrame(timestamp = 5, nowNs = 30))
        assertEquals(true, tracker.isStuck(5))
        assertEquals(false, tracker.isStuck(6))
    }

    @Test
    fun aFreshTimestampClearsTheStall() {
        val tracker = WebcamStallTracker(thresholdNs = 100, maxReopens = 2)
        assertEquals(false, tracker.onFrame(timestamp = 10, nowNs = 0))
        assertEquals(false, tracker.onFrame(timestamp = 10, nowNs = 0))
        assertEquals(true, tracker.onFrame(timestamp = 10, nowNs = 100))
        assertEquals(false, tracker.onFrame(timestamp = 20, nowNs = 200))
        assertEquals(0, tracker.reopens)
    }
}
