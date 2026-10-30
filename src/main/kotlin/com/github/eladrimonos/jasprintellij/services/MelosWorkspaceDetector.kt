package com.github.eladrimonos.jasprintellij.services

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Paths
import org.yaml.snakeyaml.Yaml

/**
 * Detects whether a project directory is part of a Dart pub workspace and/or a
 * Melos-managed monorepo, walking up parent directories since IntelliJ typically
 * opens a member package (e.g. `packages/website`), not the workspace root.
 */
object MelosWorkspaceDetector {

    data class WorkspaceInfo(
        val workspaceRoot: File,
        val melosConfigFile: File,
        val memberPackagePaths: List<String>,
        val isMelosManaged: Boolean,
    )

    private const val MAX_UPWARD_LEVELS = 4
    private const val MAX_GLOB_DEPTH = 5
    private val GLOB_CHARS = setOf('*', '?', '[', '{')
    private val SKIPPED_DIRS = setOf("build", "node_modules")
    private val logger = Logger.getInstance(MelosWorkspaceDetector::class.java)

    /** Never throws; returns null when no workspace/melos setup is found. */
    fun detect(projectDir: File): WorkspaceInfo? {
        var dir = projectDir.absoluteFile
        var level = 0

        while (level <= MAX_UPWARD_LEVELS) {
            inspectDir(dir)?.let { return it }
            if (File(dir, ".git").exists()) return null

            dir = dir.parentFile ?: return null
            level++
        }
        return null
    }

    private fun inspectDir(dir: File): WorkspaceInfo? {
        val melosYaml = File(dir, "melos.yaml")
        val melosConfig = loadYamlMap(melosYaml)
        if (melosConfig != null) {
            // Melos 7 reads members from the pub workspace; Melos 6 from `packages:` globs.
            val memberPaths = parsePubspecWorkspaceField(File(dir, "pubspec.yaml"))
                ?: expandMemberPatterns(dir, (melosConfig["packages"] as? List<*>)?.filterIsInstance<String>().orEmpty())
            return WorkspaceInfo(
                workspaceRoot = dir,
                melosConfigFile = melosYaml,
                memberPackagePaths = memberPaths,
                isMelosManaged = true,
            )
        }

        val pubspec = File(dir, "pubspec.yaml")
        if (!pubspec.exists()) return null

        val workspaceField = parsePubspecWorkspaceField(pubspec)
        if (!workspaceField.isNullOrEmpty()) {
            return WorkspaceInfo(
                workspaceRoot = dir,
                melosConfigFile = pubspec,
                memberPackagePaths = workspaceField,
                isMelosManaged = false,
            )
        }

        if (pubspecHasMelosSection(pubspec)) {
            return WorkspaceInfo(
                workspaceRoot = dir,
                melosConfigFile = pubspec,
                memberPackagePaths = emptyList(),
                isMelosManaged = true,
            )
        }

        return null
    }

    /** Resolves the `name:` field of each member package's pubspec.yaml, sorted. Skips unreadable ones. */
    fun resolveMemberPackageNames(info: WorkspaceInfo): List<String> {
        return info.memberPackagePaths.mapNotNull { relativePath ->
            val pubspec = File(File(info.workspaceRoot, relativePath), "pubspec.yaml")
            loadYamlMap(pubspec)?.get("name") as? String
        }.sorted()
    }

    /**
     * The directory of the member package named [packageName], relative to the
     * directory holding [WorkspaceInfo.melosConfigFile] and using `/` separators,
     * or null if it isn't one of the declared workspace members.
     */
    fun resolveMemberPackageRelativeDir(info: WorkspaceInfo, packageName: String): String? {
        val configDir = info.melosConfigFile.absoluteFile.parentFile ?: return null
        return info.memberPackagePaths.firstNotNullOfOrNull { relativePath ->
            val dir = File(info.workspaceRoot, relativePath)
            val name = loadYamlMap(File(dir, "pubspec.yaml"))?.get("name") as? String
            if (name == packageName) dir.absoluteFile.relativeTo(configDir).invariantSeparatorsPath else null
        }
    }

    private fun parsePubspecWorkspaceField(pubspec: File): List<String>? {
        val map = loadYamlMap(pubspec) ?: return null
        val entries = (map["workspace"] as? List<*>)?.filterIsInstance<String>() ?: return null
        return expandMemberPatterns(pubspec.absoluteFile.parentFile, entries)
    }

    /**
     * Resolves member entries relative to [root] into package directories (those holding a
     * pubspec.yaml), using `/` separators. Plain paths are kept as written; glob entries (a
     * `*` or `**` segment, as Melos and pub workspaces allow) are expanded.
     */
    internal fun expandMemberPatterns(root: File, entries: List<String>): List<String> {
        val result = LinkedHashSet<String>()
        for (entry in entries) {
            val pattern = entry.trim().removePrefix("./").trimEnd('/')
            if (pattern.isEmpty()) continue
            if (pattern.none { it in GLOB_CHARS }) {
                result += pattern
                continue
            }
            val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
            root.walkTopDown()
                .onEnter { it == root || (!it.name.startsWith(".") && it.name !in SKIPPED_DIRS) }
                .maxDepth(MAX_GLOB_DEPTH)
                .filter { it.isDirectory && it != root && File(it, "pubspec.yaml").isFile }
                .map { it.relativeTo(root).invariantSeparatorsPath }
                .filter { matcher.matches(Paths.get(it)) }
                .sorted()
                .forEach { result += it }
        }
        return result.toList()
    }

    private fun pubspecHasMelosSection(pubspec: File): Boolean {
        val map = loadYamlMap(pubspec) ?: return false
        return map["melos"] != null
    }

    private fun loadYamlMap(file: File): Map<String, Any?>? {
        if (!file.exists()) return null
        return try {
            @Suppress("UNCHECKED_CAST")
            Yaml().load(file.readText(Charsets.UTF_8)) as? Map<String, Any?>
        } catch (e: Exception) {
            logger.warn("Could not parse YAML file ${file.absolutePath}: ${e.message}")
            null
        }
    }
}
