package ai.kilocode.backend.cli

import ai.kilocode.log.KiloLog
import com.intellij.jna.JnaLoader
import com.intellij.openapi.util.SystemInfo
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ties the CLI process tree to the IDE process via a Windows Job Object configured with
 * `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`.
 *
 * The job handle is held open inside the IDE process. When the IDE goes away for any reason —
 * clean exit, `Runtime.halt()` on restart, a crash, or an external force-kill — its handle table
 * is torn down, the last job handle closes, and Windows terminates every process in the job. This
 * is OS-enforced and needs no cooperation from the CLI or a JVM shutdown hook, so the CLI can never
 * be orphaned. That matters on Windows specifically: an orphaned child inherits IDE handles and
 * wedges the next IDE launch until it is killed by hand.
 *
 * Windows-only and strictly best-effort: [assign] returns null on other platforms, when JNA is not
 * available, or if any native call fails, and callers fall back to their existing process-tree kill.
 * So this can only ever improve teardown, never regress it.
 *
 * We map the three Job Object functions directly against `kernel32` because the JNA build bundled
 * with the IDE does not expose them. This relies only on the stable core JNA runtime and the
 * kernel32 ABI, so it is unaffected by JNA version changes across IDE releases.
 *
 * JNA is deliberately NOT bundled with the plugin (unlike other third-party deps): it loads a
 * single native `jnidispatch` library per process, so a second copy would collide with the
 * platform's. Like `kotlinx.coroutines`, it must come from the IDE's own classloader — here via
 * `lib/util-8.jar`, which already backs platform APIs such as `WinProcessManager` and `NioFiles`.
 */
internal class KiloProcessJob private constructor(
    private val lib: Kernel32Job,
    private val handle: Pointer,
    private val log: KiloLog,
) {
    private val closed = AtomicBoolean(false)

    /**
     * Close the job handle. When this is the last open handle, kill-on-close terminates the whole
     * CLI process tree immediately. Idempotent, and safe to call after the tree has already exited.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { lib.CloseHandle(handle) }
            .onFailure { log.info("Closing CLI job object handle failed: ${it.message}") }
    }

    companion object {
        // kernel32 / winnt.h constants
        private const val JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000
        private const val JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9
        private const val PROCESS_TERMINATE = 0x0001
        private const val PROCESS_SET_QUOTA = 0x0100
        // sizeof(JOBOBJECT_EXTENDED_LIMIT_INFORMATION) and offsetof(BasicLimitInformation.LimitFlags)
        // on 64-bit Windows. We only ever write LimitFlags, so a raw buffer avoids modelling the
        // full nested struct and any field-alignment mistakes.
        private const val EXTENDED_LIMIT_SIZE = 144L
        private const val LIMIT_FLAGS_OFFSET = 16L

        private val lib: Kernel32Job? by lazy {
            runCatching { Native.load("kernel32", Kernel32Job::class.java) }.getOrNull()
        }

        /**
         * Assign the process tree rooted at [pid] to a kill-on-close job. Returns null (and logs the
         * reason) when not applicable or on any failure, so the caller keeps its existing teardown.
         */
        fun assign(pid: Long, log: KiloLog): KiloProcessJob? {
            if (!SystemInfo.isWindows) return null
            if (Native.POINTER_SIZE != 8) return null
            if (!JnaLoader.isLoaded()) {
                log.info("JNA unavailable; CLI kill-on-close job skipped (pid=$pid)")
                return null
            }
            val k = lib ?: run {
                log.info("kernel32 unavailable; CLI kill-on-close job skipped (pid=$pid)")
                return null
            }
            return runCatching { create(k, pid, log) }
                .getOrElse { log.warn("Failed to assign CLI to kill-on-close job (pid=$pid)", it); null }
        }

        private fun create(k: Kernel32Job, pid: Long, log: KiloLog): KiloProcessJob? {
            val job = k.CreateJobObjectW(null, null)
            if (job == null || job == Pointer.NULL) {
                log.warn("CreateJobObject returned null (pid=$pid)")
                return null
            }
            val info = Memory(EXTENDED_LIMIT_SIZE)
            info.clear()
            info.setInt(LIMIT_FLAGS_OFFSET, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE)
            if (!k.SetInformationJobObject(job, JOB_OBJECT_EXTENDED_LIMIT_INFORMATION, info, EXTENDED_LIMIT_SIZE.toInt())) {
                log.warn("SetInformationJobObject failed (pid=$pid)")
                k.CloseHandle(job)
                return null
            }
            val proc = k.OpenProcess(PROCESS_TERMINATE or PROCESS_SET_QUOTA, false, pid.toInt())
            if (proc == null || proc == Pointer.NULL) {
                log.warn("OpenProcess failed (pid=$pid)")
                k.CloseHandle(job)
                return null
            }
            val assigned = k.AssignProcessToJobObject(job, proc)
            k.CloseHandle(proc)
            if (!assigned) {
                log.warn("AssignProcessToJobObject failed (pid=$pid)")
                k.CloseHandle(job)
                return null
            }
            log.info("CLI assigned to kill-on-close job object (pid=$pid)")
            return KiloProcessJob(k, job, log)
        }
    }
}

/**
 * Minimal direct mapping of the `kernel32` Job Object functions absent from the IDE's bundled JNA.
 * Handles are plain [Pointer]s and BOOL maps to [Boolean]; all functions have stable ABIs.
 */
internal interface Kernel32Job : StdCallLibrary {
    fun CreateJobObjectW(attributes: Pointer?, name: Pointer?): Pointer?
    fun SetInformationJobObject(job: Pointer, infoClass: Int, info: Pointer, length: Int): Boolean
    fun AssignProcessToJobObject(job: Pointer, process: Pointer): Boolean
    fun OpenProcess(access: Int, inheritHandle: Boolean, pid: Int): Pointer?
    fun CloseHandle(handle: Pointer): Boolean
}
