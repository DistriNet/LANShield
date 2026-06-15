package org.distrinet.lanshield

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the app-id / package resolution logic in LANShieldApplication.kt. Runs under
 * Robolectric so a real [ApplicationInfo] can be constructed; [PackageManager] is mocked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PackageResolutionTest {

    private val pm: PackageManager = mockk(relaxed = true)

    private fun appInfo(flagBits: Int, pkg: String? = "com.example"): ApplicationInfo =
        ApplicationInfo().apply { flags = flagBits; packageName = pkg }

    // ---- applicationInfoIsSystem ----

    @Test
    fun `system and updated-system flags mark an app as system`() {
        assertThat(applicationInfoIsSystem(appInfo(ApplicationInfo.FLAG_SYSTEM))).isTrue()
        assertThat(applicationInfoIsSystem(appInfo(ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))).isTrue()
        assertThat(applicationInfoIsSystem(appInfo(0))).isFalse()
    }

    @Test
    fun `chrome is forced non-system even with the system flag`() {
        assertThat(applicationInfoIsSystem(appInfo(ApplicationInfo.FLAG_SYSTEM, "com.android.chrome")))
            .isFalse()
    }

    @Test
    fun `null package name falls through to the flag check`() {
        assertThat(applicationInfoIsSystem(appInfo(ApplicationInfo.FLAG_SYSTEM, pkg = null))).isTrue()
    }

    // ---- getPackageNameFromUid ----

    @Test
    fun `special uids map to reserved names without consulting the package manager`() {
        assertThat(getPackageNameFromUid(0, pm)).isEqualTo(PACKAGE_NAME_ROOT)
        assertThat(getPackageNameFromUid(-1, pm)).isEqualTo(PACKAGE_NAME_UNKNOWN)
        assertThat(getPackageNameFromUid(1000, pm)).isEqualTo(PACKAGE_NAME_SYSTEM)
    }

    @Test
    fun `single-package uid resolves to that package`() {
        every { pm.getPackagesForUid(123) } returns arrayOf("com.foo")
        assertThat(getPackageNameFromUid(123, pm)).isEqualTo("com.foo")
    }

    @Test
    fun `shared uid falls back to the shared name`() {
        every { pm.getPackagesForUid(124) } returns arrayOf("com.a", "com.b")
        every { pm.getNameForUid(124) } returns "shared.uid.name"
        assertThat(getPackageNameFromUid(124, pm)).isEqualTo("shared.uid.name")
    }

    @Test
    fun `unresolvable uid becomes Unknown`() {
        every { pm.getPackagesForUid(125) } returns null
        every { pm.getNameForUid(125) } returns null
        assertThat(getPackageNameFromUid(125, pm)).isEqualTo(PACKAGE_NAME_UNKNOWN)
    }

    // ---- lookupPackageMetadata (via getPackageMetadata) ----

    @Test
    fun `reserved package names resolve without the package manager`() {
        assertThat(getPackageMetadata(PACKAGE_NAME_UNKNOWN, pm).isSystem).isFalse()
        assertThat(getPackageMetadata(PACKAGE_NAME_ROOT, pm).isSystem).isTrue()
        assertThat(getPackageMetadata(PACKAGE_NAME_SYSTEM, pm).isSystem).isTrue()
    }

    @Test
    fun `shared phone and play-services uids are treated as system`() {
        assertThat(getPackageMetadata("android.uid.phone:1001", pm).isSystem).isTrue()
        val playServices = getPackageMetadata("com.google.uid.shared:5000", pm)
        assertThat(playServices.isSystem).isTrue()
        assertThat(playServices.packageLabel).isEqualTo(PACKAGE_NAME_PLAY_SERVICES)
    }

    @Test
    fun `a real package is resolved via the package manager`() {
        val name = "com.test.realapp"
        every { pm.getApplicationInfo(name, PackageManager.GET_META_DATA) } returns appInfo(0, name)
        every { pm.getApplicationLabel(any()) } returns "Real App"

        val metadata = getPackageMetadata(name, pm)
        assertThat(metadata.packageLabel).isEqualTo("Real App")
        assertThat(metadata.isSystem).isFalse()
    }

    @Test
    fun `an unknown package falls back to its own name`() {
        val name = "com.missing.app"
        every { pm.getApplicationInfo(name, PackageManager.GET_META_DATA) } throws
            PackageManager.NameNotFoundException()

        val metadata = getPackageMetadata(name, pm)
        assertThat(metadata.packageLabel).isEqualTo(name)
        assertThat(metadata.isSystem).isFalse()
    }
}
