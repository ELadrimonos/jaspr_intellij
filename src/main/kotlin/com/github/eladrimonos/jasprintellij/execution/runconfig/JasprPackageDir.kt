package com.github.eladrimonos.jasprintellij.execution.runconfig

import com.github.eladrimonos.jasprintellij.services.JasprTooling
import com.intellij.openapi.project.Project
import java.io.File

/**
 * The Jaspr package a run configuration serves when it invokes `jaspr daemon` directly
 * (no Melos script): the one chosen in [JasprRunConfigurationOptions.packageDir], or else
 * the project's first Jaspr package. In a plain pub workspace the opened root is usually
 * not an app, so running from it would fail.
 */
internal object JasprPackageDir {

    fun resolve(project: Project, options: JasprRunConfigurationOptions): File? {
        val basePath = project.basePath ?: return null
        return resolve(File(basePath), options.packageDir) { JasprTooling.findJasprProjectDir(project) }
    }

    fun resolve(projectDir: File, packageDir: String, firstJasprPackage: () -> File?): File =
        if (packageDir.isNotBlank()) File(packageDir).let { if (it.isAbsolute) it else File(projectDir, packageDir) }
        else firstJasprPackage() ?: projectDir

    /** [dir] relative to [projectDir] with `/` separators, as stored in the run configuration. */
    fun toStored(projectDir: File, dir: File): String =
        dir.absoluteFile.relativeToOrNull(projectDir.absoluteFile)?.invariantSeparatorsPath ?: dir.path

    /**
     * Re-bases [rawPath] (stored relative to [projectDir], or absolute) onto [packageDir], the
     * daemon's cwd. A file outside the package is passed as an absolute path.
     */
    fun rebase(projectDir: File, packageDir: File, rawPath: String): String {
        val absolute = File(rawPath).let { if (it.isAbsolute) it else File(projectDir, rawPath) }.absoluteFile
        val relative = absolute.relativeToOrNull(packageDir.absoluteFile)
        return if (relative != null && !relative.path.startsWith("..")) relative.invariantSeparatorsPath
        else absolute.path
    }
}
