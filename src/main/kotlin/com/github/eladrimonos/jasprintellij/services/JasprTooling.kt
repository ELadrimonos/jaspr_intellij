package com.github.eladrimonos.jasprintellij.services

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import java.io.File
import java.nio.charset.StandardCharsets
import org.yaml.snakeyaml.Yaml

class JasprCliMissingException(message: String) : ConfigurationException(message)

class JasprTooling(
    private val cliRunner: CliRunner = DefaultCliRunner,
    private val environmentProvider: () -> Map<String, String> = { EnvironmentUtil.getEnvironmentMap() },
    private val logger: Logger = Logger.getInstance(JasprTooling::class.java),
) {
    companion object {
        const val MAX_LOCKFILE_SEARCH_LEVELS = 5

        fun isJasprProject(project: Project): Boolean = findJasprProjectDir(project) != null

        /**
         * Resolves the directory of the actual Jaspr package to run tooling against:
         * the opened project root if it directly depends on jaspr, otherwise the
         * nearest workspace/Melos member package that does (so the daemon runs with
         * that package's own `analysis_options.yaml` / `.dart_tool` in scope).
         */
        fun findJasprProjectDir(project: Project): File? {
            val basePath = project.basePath ?: return null
            val projectDir = File(basePath)

            if (pubspecMentionsJaspr(File(projectDir, "pubspec.yaml"))) return projectDir

            // Root pubspec.yaml (or melos.yaml) may be a bare workspace manifest with no
            // jaspr dependency of its own — check member packages before giving up.
            val workspaceInfo = MelosWorkspaceDetector.detect(projectDir) ?: return null
            return workspaceInfo.memberPackagePaths
                .map { relativePath -> File(workspaceInfo.workspaceRoot, relativePath) }
                .firstOrNull { memberDir -> pubspecMentionsJaspr(File(memberDir, "pubspec.yaml")) }
        }

        // Matches an actual `jaspr:` dependency declaration line (e.g. "  jaspr: ^0.23.4"),
        // not any incidental mention of the word "jaspr" elsewhere in the file — root
        // pubspec.yaml in a Melos monorepo commonly references "jaspr_cli:jaspr" inside
        // melos script definitions, which is not a dependency declaration.
        private val JASPR_DEPENDENCY_REGEX = Regex("""(?m)^\s*jaspr\s*:\s*\S""")

        private fun pubspecMentionsJaspr(pubspec: File): Boolean {
            if (!pubspec.exists()) return false
            return try {
                declaresJasprDependency(pubspec.readText())
            } catch (e: Exception) {
                false
            }
        }

        /** Whether pubspec.yaml [text] declares a `jaspr` dependency. */
        fun declaresJasprDependency(text: String): Boolean = JASPR_DEPENDENCY_REGEX.containsMatchIn(text)
    }

    private fun dartExecutable(sdkPath: String): String {
        val dartExeName = if (SystemInfo.isWindows) "dart.exe" else "dart"
        return File(sdkPath, "bin/$dartExeName").absolutePath
    }

    /**
     * Verifies that Dart and jaspr_cli can be run, throwing a descriptive
     * [ConfigurationException] otherwise. Returns the installed jaspr_cli version
     * (as [readJasprCliVersion] would) so callers don't spawn `jaspr --version` again.
     */
    fun preflightCheck(sdkPath: String): String? {
        val dartExe = dartExecutable(sdkPath)

        fun runOrThrow(vararg args: String, hint: String, isCliCheck: Boolean = false): ProcessOutput {
            val cmd = GeneralCommandLine()
                .withExePath(dartExe)
                .withParameters(*args)
                .withCharset(StandardCharsets.UTF_8)
                .withEnvironment(environmentProvider())

            val out = cliRunner.run(cmd)
            if (out.exitCode != 0) {
                val message = buildString {
                    appendLine(hint)
                    appendLine("Command: dart ${args.joinToString(" ")}")
                    if (out.stderr.isNotBlank()) appendLine("stderr:\n${out.stderr}")
                    if (out.stdout.isNotBlank()) appendLine("stdout:\n${out.stdout}")
                }.trim()

                if (isCliCheck) {
                    throw JasprCliMissingException(message)
                } else {
                    throw ConfigurationException(message)
                }
            }
            return out
        }

        runOrThrow("--version", hint = "Failed to run Dart. Verify that the selected SDK path is valid.")

        val cliOut = runOrThrow(
            "pub", "global", "run", "jaspr_cli:jaspr", "--version",
            hint = "Could not find jaspr_cli via 'dart pub global run'. " +
                    "Install it with: dart pub global activate jaspr_cli (network access is required the first time).",
            isCliCheck = true
        )
        return extractSemver(cliOut.stdout.ifBlank { cliOut.stderr })
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

    /** Version of the globally activated melos (`dart pub global run melos --version`), or null. */
    fun readMelosVersion(sdkPath: String): String? {
        val cmd = GeneralCommandLine()
            .withExePath(dartExecutable(sdkPath))
            .withParameters("pub", "global", "run", "melos", "--version")
            .withCharset(StandardCharsets.UTF_8)
            .withEnvironment(environmentProvider())
        val out = cliRunner.run(cmd)
        if (out.exitCode != 0) return null
        return extractSemver(out.stdout.ifBlank { out.stderr })
    }

    fun readFrameworkVersionFromPubspec(projectDir: File): String? {
        val pubspec = File(projectDir, "pubspec.yaml")
        if (!pubspec.exists()) return null

        return try {
            parseFrameworkVersionFromPubspec(pubspec.readText(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            logger.warn("Could not read pubspec.yaml for framework version: ${e.message}")
            null
        }
    }

    /** The version from the `jaspr:` dependency constraint in pubspec.yaml [text], or null. */
    fun parseFrameworkVersionFromPubspec(text: String): String? {
        val regex = Regex("""(?m)^\s*jaspr\s*:\s*(.+?)\s*$""")
        val match = regex.find(text) ?: return null
        val rawSpec = match.groupValues.getOrNull(1)?.trim()
        if (rawSpec.isNullOrBlank()) return null

        return normalizeJasprSpec(rawSpec)
    }

    /**
     * Reads the version of the `jaspr` package actually resolved by pub, from `pubspec.lock`.
     * Looks in [projectDir] first and then walks up the parent directories, since a pub
     * workspace keeps a single lockfile at the workspace root rather than in each member.
     */
    fun readResolvedFrameworkVersion(projectDir: File): String? {
        var dir: File? = projectDir.absoluteFile
        var levels = 0
        while (dir != null && levels <= MAX_LOCKFILE_SEARCH_LEVELS) {
            val lockfile = File(dir, "pubspec.lock")
            if (lockfile.isFile) return readJasprVersionFromLockfile(lockfile)
            dir = dir.parentFile
            levels++
        }
        return null
    }

    private fun readJasprVersionFromLockfile(lockfile: File): String? {
        return try {
            parseJasprVersionFromLockfile(lockfile.readText(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            logger.warn("Could not read pubspec.lock for framework version: ${e.message}")
            null
        }
    }

    /** The resolved `jaspr` package version in pubspec.lock [text], or null. Throws on malformed YAML. */
    fun parseJasprVersionFromLockfile(text: String): String? {
        val root = Yaml().load<Any?>(text) as? Map<*, *> ?: return null
        val packages = root["packages"] as? Map<*, *> ?: return null
        val jaspr = packages["jaspr"] as? Map<*, *> ?: return null
        val version = jaspr["version"]?.toString() ?: return null
        return extractSemver(version)
    }

    /**
     * Whether the installed jaspr_cli and the project's jaspr version are far enough apart
     * to warn about. Only major.minor is compared: patch releases are compatible, and a
     * pubspec constraint like `^0.23.0` legitimately resolves to a newer patch.
     */
    fun isVersionMismatch(cliVersion: String, frameworkVersion: String): Boolean {
        val cli = majorMinor(cliVersion) ?: return false
        val framework = majorMinor(frameworkVersion) ?: return false
        return cli != framework
    }

    private fun majorMinor(version: String): Pair<Int, Int>? {
        val semver = extractSemver(version) ?: return null
        val parts = semver.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return major to minor
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

    fun isVersionAtLeast(current: String?, target: String): Boolean {
        if (current == null) return false
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val targetParts = target.split(".").mapNotNull { it.toIntOrNull() }

        for (i in 0 until maxOf(currentParts.size, targetParts.size)) {
            val c = currentParts.getOrElse(i) { 0 }
            val t = targetParts.getOrElse(i) { 0 }
            if (c > t) return true
            if (c < t) return false
        }
        return true
    }
}