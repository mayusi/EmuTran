package io.github.mayusi.emutran.domain.install

import android.content.Context
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File

/**
 * Unit tests for the DEBUGGING fix on the EMULATOR install path: the
 * [PackageInstallerInstaller] now gates on the per-app "Install unknown apps"
 * grant before touching the session API, the same way the self-update path
 * gates via [IntentInstaller].
 *
 * Root cause: without the grant, the system installer hangs on
 * STATUS_PENDING_USER_ACTION (no timeout) or fails cryptically — the user's
 * "starts then fails". The fix returns [InstallResult.NeedsPermission] early so
 * the UI can deep-link the user to settings and retry.
 *
 * These are plain JVM unit tests — the [Context]/[PackageManager] chain is
 * MockK'd so no Android framework is needed. We assert on the early-return seam
 * (canRequestPackageInstalls() == false) and confirm NO session is created.
 */
class PackageInstallerPermissionGateTest {

    private val packageManager = mockk<PackageManager>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    private val installer: PackageInstallerInstaller

    init {
        every { context.packageManager } returns packageManager
        installer = PackageInstallerInstaller(context)
    }

    // ── Permission gate ───────────────────────────────────────────────────────

    @Test
    fun `install returns NeedsPermission when install-unknown-apps is not granted`() = runTest {
        every { packageManager.canRequestPackageInstalls() } returns false

        val result = installer.install(File("/tmp/does-not-matter.apk"))

        assert(result is InstallResult.NeedsPermission) {
            "expected NeedsPermission when the OS won't allow installs, got $result"
        }
    }

    @Test
    fun `install never opens a PackageInstaller session when permission is missing`() = runTest {
        every { packageManager.canRequestPackageInstalls() } returns false
        // Spy on the session entry point so we can prove we bailed before it.
        val packageInstaller = mockk<PackageInstaller>(relaxed = true)
        every { packageManager.packageInstaller } returns packageInstaller

        installer.install(File("/tmp/does-not-matter.apk"))

        // The early return must short-circuit BEFORE any session is created.
        verify(exactly = 0) { packageManager.packageInstaller }
        verify(exactly = 0) { packageInstaller.createSession(any()) }
    }

    // ── Router delegation ─────────────────────────────────────────────────────

    @Test
    fun `router delegates the permission-gate helpers to the PackageInstaller path`() {
        val packageInstaller = mockk<PackageInstallerInstaller>(relaxed = true)
        every { packageInstaller.canRequestInstalls() } returns false
        val router = InstallerRouter(
            shizuku = mockk(relaxed = true),
            shizukuAvailability = mockk(relaxed = true),
            packageInstaller = packageInstaller,
        )

        assert(!router.canRequestInstalls()) {
            "router.canRequestInstalls() should reflect the PackageInstaller's gate"
        }
        router.openInstallPermissionSettings()

        verify(exactly = 1) { packageInstaller.canRequestInstalls() }
        verify(exactly = 1) { packageInstaller.openManageUnknownAppsSettings() }
    }
}
