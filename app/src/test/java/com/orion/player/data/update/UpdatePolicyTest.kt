package com.orion.player.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    @Test fun onlyNewUnblockedReleaseDownloads() {
        assertFalse(UpdatePolicy.shouldDownload(67, 67, false, null, 0, 100))
        assertFalse(UpdatePolicy.shouldDownload(67, 66, true, "BLOCKED", 0, 100))
        assertFalse(UpdatePolicy.shouldDownload(67, 66, true, "VERIFIED", 0, 100))
        assertFalse(UpdatePolicy.shouldDownload(67, 66, true, "FAILED", 200, 100))
        assertTrue(UpdatePolicy.shouldDownload(67, 66, true, "FAILED", 200, 201))
        assertTrue(UpdatePolicy.shouldDownload(68, 66, false, "BLOCKED", 0, 100))
    }

    @Test fun replacementNeedsVisiblePlaybackBeforeBeingHealthy() {
        assertFalse(UpdatePolicy.isHealthy("INSTALLING", 66, 67, true, true, 200, 100))
        assertFalse(UpdatePolicy.isHealthy("INSTALLING", 67, 67, false, true, 200, 100))
        assertFalse(UpdatePolicy.isHealthy("INSTALLING", 67, 67, true, false, 200, 100))
        assertFalse(UpdatePolicy.isHealthy("INSTALLING", 67, 67, true, true, 99, 100))
        assertTrue(UpdatePolicy.isHealthy("INSTALLING", 67, 67, true, true, 200, 100))
    }
}
