package com.github.eladrimonos.jasprintellij.services

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import java.io.File
import java.nio.charset.StandardCharsets

class JasprTooling(
    private val cliRunner: CliRunner = DefaultCliRunner,
    private val environmentProvider: () -> Map<String, String> = { EnvironmentUtil.getEnvironmentMap() },
    private val logger: Logger = Logger.getInstance(JasprTooling::class.java),
) {
    private fun dartExecutable(sdkPath: String): String {
        val dartExeName = if (SystemInfo.isWindows) "dart.exe" else "dart"
        return File(sdkPath, "bin/$dartExeName").absolutePath
    }

    fun preflightCheck(sdkPath: String) {
        val dartExe = dartExecutable(sdkPath)

        fun runOrThrow(vararg args: String, hint: String) {
            val cmd = GeneralCommandLine()
                .withExePath(dartExe)
                .withParameters(*args)
                .withCharset(StandardCharsets.UTF_8)
                .withEnvironment(environmentProvider())

            val out = cliRunner.run(cmd)
            if (out.exitCode != 0) {
                throw ConfigurationException(
                    buildString {
                        appendLine(hint)
                        appendLine("Command: dart ${args.joinToString(" ")}")
                        if (out.stderr.isNotBlank()) appendLine("stderr:\n${out.stderr}")
                        if (out.stdout.isNotBlank()) appendLine("stdout:\n${out.stdout}")
                    }.trim()
                )
            }
        }

        runOrThrow("--version", hint = "Failed to run Dart. Verify that the selected SDK path is valid.")

        runOrThrow(
            "pub", "global", "run", "jaspr_cli:jaspr", "--version",
            hint = "Could not find jaspr_cli via 'dart pub global run'. " +
                    "Install it with: dart pub global activate jaspr_cli (network access is required the first time)."
        )
    }

    fun readJasprCliVersion(sdkPath: String): String? {
        val dartExe = dartExecutable(sdkPath)

        val cmd = GeneralCommandLine()
            .withExePath(dartExe)
            .withParameters("pub", "global", "run", "jaspr_cli:jaspr", "--version")
            .withCharset(StandardCharsets.UTF_8)
            .withEnvironment(environmentProvider())

        val out: ProcessOutput = cliRunner.run(cmd)
        if (out.exitCode != 0) {
            logger.warn(
                "Could not read jaspr_cli version. stderr=${out.stderr.take(500)} stdout=${out.stdout.take(500)}"
            )
            return null
        }

        val text = (out.stdout.ifBlank { out.stderr }).trim()
        if (text.isBlank()) return null

        return extractSemver(text)
    }

    fun readFrameworkVersionFromPubspec(projectDir: File): String? {
        val pubspec = File(projectDir, "pubspec.yaml")
        if (!pubspec.exists()) return null

        return try {
            val text = pubspec.readText(StandardCharsets.UTF_8)

            val regex = Regex("""(?m)^\s*jaspr\s*:\s*(.+?)\s*$""")
            val match = regex.find(text) ?: return null
            val rawSpec = match.groupValues.getOrNull(1)?.trim()
            if (rawSpec.isNullOrBlank()) return null

            normalizeJasprSpec(rawSpec)
        } catch (e: Exception) {
            logger.warn("Could not read pubspec.yaml for framework version: ${e.message}")
            null
        }
    }

    private fun normalizeJasprSpec(spec: String): String? {
        // Examples we want to handle:
        // "^1.2.3" -> "1.2.3"
        // "1.2.3" -> "1.2.3"
        // ">=1.2.0 <2.0.0" -> "1.2.0" (best-effort; you can change policy)
        // "any" / "null" -> null
        val trimmed = spec.trim()
        if (trimmed.equals("any", ignoreCase = true)) return null
        return extractSemver(trimmed)
    }

    private fun extractSemver(text: String): String? {
        // Extracts the first occurrence like 1.2.3 (optionally with -pre / +build ignored here)
        // If you need pre-release awareness, extend it.
        val m = Regex("""\b(\d+\.\d+\.\d+)\b""").find(text) ?: return null
        return m.groupValues.getOrNull(1)
    }
}