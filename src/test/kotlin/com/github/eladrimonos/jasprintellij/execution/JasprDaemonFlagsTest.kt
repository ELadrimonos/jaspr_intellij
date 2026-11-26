package com.github.eladrimonos.jasprintellij.execution

import com.github.eladrimonos.jasprintellij.execution.runconfig.JasprDaemonFlags
import com.github.eladrimonos.jasprintellij.execution.runconfig.JasprRunConfigurationOptions
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class JasprDaemonFlagsTest : BasePlatformTestCase() {

    private fun options(block: JasprRunConfigurationOptions.() -> Unit = {}) =
        JasprRunConfigurationOptions().apply(block)

    fun testLegacyCli_UsesModeAndPortFlags() {
        val args = JasprDaemonFlags.build(options {
            mode = "reload"
            webPort = "5467"
            proxyPort = "5567"
        }, "0.23.4")

        assertEquals(listOf("--mode", "reload", "--web-port", "5467", "--proxy-port", "5567"), args)
    }

    fun testLegacyCli_DefaultModeIsRefresh() {
        assertEquals(listOf("--mode", "refresh"), JasprDaemonFlags.build(options(), "0.23.4"))
    }

    fun testLegacyCli_SkipsValuesUnknownToMode() {
        assertEquals(emptyList<String>(), JasprDaemonFlags.build(options { mode = "restart" }, "0.23.4"))
        assertEquals(emptyList<String>(), JasprDaemonFlags.build(options { mode = "none" }, "0.23.4"))
    }

    fun testUnknownCliVersion_TreatedAsLegacy() {
        assertEquals(listOf("--mode", "refresh"), JasprDaemonFlags.build(options(), null))
    }

    fun testCli024_UsesAutoAndDropsPortFlags() {
        val args = JasprDaemonFlags.build(options {
            mode = "restart"
            port = "8080"
            webPort = "5467"
            proxyPort = "5567"
        }, "0.24.0")

        assertEquals(listOf("--auto", "restart", "--port", "8080"), args)
    }

    fun testCli024_NoneOmitsAuto() {
        assertEquals(emptyList<String>(), JasprDaemonFlags.build(options { mode = "none" }, "0.24.0"))
    }

    fun testCli024Beta_IsTreatedAs024() {
        // JasprTooling.readJasprCliVersion strips the pre-release suffix of "0.24.0-beta.0".
        assertTrue(JasprDaemonFlags.supportsAutoAction("0.24.0"))
        assertFalse(JasprDaemonFlags.supportsAutoAction("0.23.4"))
    }

    fun testHasIgnoredPortOptions() {
        val withPorts = options { webPort = "5467" }
        assertTrue(JasprDaemonFlags.hasIgnoredPortOptions(withPorts, "0.24.0"))
        assertFalse(JasprDaemonFlags.hasIgnoredPortOptions(withPorts, "0.23.4"))
        assertFalse(JasprDaemonFlags.hasIgnoredPortOptions(options(), "0.24.0"))
    }

    fun testCommonFlagsAndRebase() {
        val args = JasprDaemonFlags.build(options {
            mode = "none"
            verbose = true
            input = "apps/web/lib/main.server.dart"
            buildMode = "release"
            experimentalWasm = true
            managedBuildOptions = false
            skipServer = true
            dartDefine = "A=1\n\nB=2"
            dartDefineFromFile = "apps/web/env.json"
        }, "0.24.0", rebasePath = { it.removePrefix("apps/web/") })

        assertEquals(
            listOf(
                "--verbose",
                "--input", "lib/main.server.dart",
                "--release",
                "--experimental-wasm",
                "--no-managed-build-options",
                "--skip-server",
                "--dart-define", "A=1",
                "--dart-define", "B=2",
                "--dart-define-from-file", "env.json",
            ),
            args,
        )
    }
}
