package com.github.eladrimonos.jasprintellij.services

import java.io.File

/**
 * Re-bases file-path run-configuration flags (`--input`, `--dart-define-from-file`)
 * so they resolve correctly under `melos run`, whose underlying command executes
 * with the target PACKAGE directory as cwd — not the workspace root that
 * `project.basePath`-relative paths (as stored by [JasprSettingsEditor][com.github.eladrimonos.jasprintellij.execution.runconfig.JasprSettingsEditor])
 * assume.
 */
object MelosPathResolver {

    /**
     * Given [rawPath] as stored (relative to [basePath], or already absolute),
     * returns it re-based relative to the nearest ancestor directory containing a
     * pubspec.yaml — i.e. the actual package directory `melos run` will use as its
     * cwd. Falls back to [rawPath] unchanged if no pubspec.yaml is found above it,
     * so a value we can't confidently re-base is never silently broken.
     */
    fun rebaseRelativeToPackage(basePath: String, rawPath: String): String {
        val absolute = File(rawPath).let { if (it.isAbsolute) it else File(basePath, rawPath) }

        var dir = absolute.parentFile
        while (dir != null) {
            if (File(dir, "pubspec.yaml").exists()) {
                val relative = dir.toPath().relativize(absolute.toPath()).toString()
                return if (File.separatorChar != '/') relative.replace(File.separatorChar, '/') else relative
            }
            dir = dir.parentFile
        }
        return rawPath
    }
}
