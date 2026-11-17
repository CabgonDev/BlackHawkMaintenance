package com.cabgon.blackhawk.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadProgressTrackerTest {
    @Test fun weightsPendingBytesAndRequiresSuccessfulCompletion() {
        val tracker = DownloadProgressTracker()
        tracker.reset(100L, 900L)
        tracker.completeIndex()
        assertEquals(10, tracker.percent())
        tracker.updateManual(1, 1, 1f, 900L)
        assertEquals(99, tracker.percent())
        tracker.completeManuals()
        assertEquals(100, tracker.percent())
    }

    @Test fun indexOnlyDownloadDoesNotStopAtFiftyPercent() {
        val tracker = DownloadProgressTracker()
        tracker.reset(100L, 0L)
        tracker.updateIndex(0.5f)
        assertEquals(50, tracker.percent())
        tracker.completeIndex()
        tracker.completeManuals()
        assertEquals(100, tracker.percent())
    }

    @Test fun unknownSizesAndSkippedManualsAdvanceWithoutFalseCompletion() {
        val tracker = DownloadProgressTracker()
        tracker.reset()
        tracker.completeIndex()
        tracker.updateManual(2, 2, 1f)
        assertEquals(99, tracker.percent())
        tracker.completeManuals()
        assertEquals(100, tracker.percent())
        tracker.reset()
        assertEquals(0, tracker.percent())
    }
}
