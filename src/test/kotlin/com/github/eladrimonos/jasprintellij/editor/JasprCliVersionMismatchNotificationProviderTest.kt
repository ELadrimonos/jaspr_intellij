package com.github.eladrimonos.jasprintellij.editor

import com.github.eladrimonos.jasprintellij.services.JasprToolingDaemonService
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class JasprCliVersionMismatchNotificationProviderTest : BasePlatformTestCase() {

    private val provider = JasprCliVersionMismatchNotificationProvider()

    private fun installCli(version: String?) {
        project.getService(JasprToolingDaemonService::class.java).cliVersion = version
    }

    override fun tearDown() {
        try {
            installCli(null)
        } finally {
            super.tearDown()
        }
    }

    private fun dartFileIn(dir: String): VirtualFile =
        myFixture.addFileToProject("$dir/lib/main.dart", "void main() {}\n").virtualFile

    private fun hasBanner(file: VirtualFile): Boolean = provider.collectNotificationData(project, file) != null

    fun testWarnsForPackageNotMatchingCli() {
        installCli("0.23.5")
        myFixture.addFileToProject("apps/beta/pubspec.yaml", "name: beta\ndependencies:\n  jaspr: 0.24.0-beta.0\n")
        assertTrue(hasBanner(dartFileIn("apps/beta")))
    }

    fun testChecksEachMonorepoPackageOnItsOwn() {
        installCli("0.23.5")
        myFixture.addFileToProject("apps/stable/pubspec.yaml", "name: stable\ndependencies:\n  jaspr: ^0.23.0\n")
        myFixture.addFileToProject("apps/beta/pubspec.yaml", "name: beta\ndependencies:\n  jaspr: 0.24.0-beta.0\n")
        assertFalse(hasBanner(dartFileIn("apps/stable")))
        assertTrue(hasBanner(dartFileIn("apps/beta")))
    }

    fun testPrefersLockfileOverConstraint() {
        installCli("0.24.0-beta.0")
        // ^0.23.0 alone would mismatch; the lockfile says what pub actually resolved.
        myFixture.addFileToProject("app/pubspec.yaml", "name: app\ndependencies:\n  jaspr: ^0.23.0\n")
        myFixture.addFileToProject("app/pubspec.lock", "packages:\n  jaspr:\n    version: \"0.24.0-beta.0\"\n")
        assertFalse(hasBanner(dartFileIn("app")))
    }

    fun testIgnoresPatchDifferences() {
        installCli("0.23.5")
        myFixture.addFileToProject("app/pubspec.yaml", "name: app\ndependencies:\n  jaspr: ^0.23.0\n")
        assertFalse(hasBanner(dartFileIn("app")))
    }

    fun testIgnoresPackagesWithoutJaspr() {
        installCli("0.23.5")
        // A workspace root lockfile resolves jaspr for every member, even ones that don't use it.
        myFixture.addFileToProject("pubspec.lock", "packages:\n  jaspr:\n    version: \"0.24.0-beta.0\"\n")
        myFixture.addFileToProject("packages/utils/pubspec.yaml", "name: utils\ndependencies:\n  path: ^1.9.0\n")
        assertFalse(hasBanner(dartFileIn("packages/utils")))
    }

    fun testSilentUntilCliVersionIsKnown() {
        installCli(null)
        myFixture.addFileToProject("app/pubspec.yaml", "name: app\ndependencies:\n  jaspr: 0.24.0-beta.0\n")
        assertFalse(hasBanner(dartFileIn("app")))
    }
}
