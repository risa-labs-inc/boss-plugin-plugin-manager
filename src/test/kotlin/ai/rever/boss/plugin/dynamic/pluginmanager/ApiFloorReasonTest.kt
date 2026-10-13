package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.dynamic.pluginmanager.api.BossCompat
import ai.rever.boss.plugin.dynamic.pluginmanager.api.apiFloorReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Global host properties are restored in finally; Gradle disables parallel execution. */
class ApiFloorReasonTest {
    @Test fun `API and app floors ignore prerelease and build suffixes consistently`() {
        val previousApi = System.getProperty("boss.api.version")
        val previousApp = System.getProperty("boss.app.version")
        try {
            for (host in listOf("1.0.85", "1.0.85-rc1", "1.0.85+build.7")) {
                System.setProperty("boss.api.version", host)
                System.setProperty("boss.app.version", host)
                for (floor in listOf("1.0.85", "1.0.85-rc2+build.9", "1.0.86-rc1")) {
                    assertEquals(BossCompat.isInstallable(floor), apiFloorReason(floor) == null)
                }
                assertNull(apiFloorReason("1.0.85-rc2+build.9"))
                assertNotNull(apiFloorReason("1.0.86-rc1"))
            }
        } finally {
            if (previousApi == null) System.clearProperty("boss.api.version") else System.setProperty("boss.api.version", previousApi)
            if (previousApp == null) System.clearProperty("boss.app.version") else System.setProperty("boss.app.version", previousApp)
        }
    }
}
