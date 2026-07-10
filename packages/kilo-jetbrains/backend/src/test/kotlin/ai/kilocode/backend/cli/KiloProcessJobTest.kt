package ai.kilocode.backend.cli

import ai.kilocode.backend.testing.TestLog
import com.intellij.openapi.util.SystemInfo
import kotlin.test.Test
import kotlin.test.assertNull

class KiloProcessJobTest {

    /**
     * The kill-on-close job is Windows-only. Everywhere else (including CI) [assign] must be an
     * inert no-op that never touches native code — the caller relies on that to fall back to its
     * existing process-tree kill. The Windows path is verified manually / on Windows hosts, since
     * assigning a real PID to a kill-on-close job cannot be exercised safely from the test JVM.
     */
    @Test
    fun `assign is a no-op on non-Windows platforms`() {
        if (SystemInfo.isWindows) return
        assertNull(KiloProcessJob.assign(4321L, TestLog()))
    }
}
