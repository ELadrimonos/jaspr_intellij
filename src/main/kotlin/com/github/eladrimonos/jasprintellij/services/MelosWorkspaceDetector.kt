package com.github.eladrimonos.jasprintellij.services

import com.intellij.openapi.diagnostic.Logger
import java.io.File
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
        if (melosYaml.exists() && loadYamlMap(melosYaml) != null) {
            val memberPaths = parsePubspecWorkspaceField(File(dir, "pubspec.yaml")) ?: emptyList()
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

    private fun parsePubspecWorkspaceField(pubspec: File): List<String>? {
        val map = loadYamlMap(pubspec) ?: return null
        @Suppress("UNCHECKED_CAST")
        return (map["workspace"] as? List<*>)?.filterIsInstance<String>()
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
