package com.github.eladrimonos.jasprintellij.services

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

/**
 * The jaspr version the package containing a file depends on: the one resolved in
 * pubspec.lock (also looked up in parent directories for pub workspaces), falling back
 * to the pubspec.yaml constraint. Read through the VFS and cached until either file changes.
 */
internal object JasprPackageVersion {
    private val tooling = JasprTooling()

    /** [version] of jaspr in the package at [packageDir], read from the file named [source]. */
    data class Resolved(val version: String, val source: String, val packageDir: VirtualFile)

    fun of(file: PsiFile): String? = resolve(file)?.version

    /** Must be called in a read action. */
    fun of(project: Project, file: VirtualFile): Resolved? =
        PsiManager.getInstance(project).findFile(file)?.let(::resolve)

    // Cached on the PSI file: platform rules forbid storing a CachedValue in VirtualFile user data.
    private fun resolve(file: PsiFile): Resolved? {
        val original = file.originalFile
        return CachedValuesManager.getCachedValue(original) {
            val dependencies = mutableListOf<Any>(VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
            val resolved = original.virtualFile?.let { compute(it, dependencies) }
            CachedValueProvider.Result.create(resolved, *dependencies.toTypedArray())
        }
    }

    private fun compute(file: VirtualFile, dependencies: MutableList<Any>): Resolved? {
        val pubspec = generateSequence(file.parent) { it.parent }
            .firstNotNullOfOrNull { it.findChild("pubspec.yaml") } ?: return null
        dependencies += pubspec
        val pubspecText = runCatching { VfsUtilCore.loadText(pubspec) }.getOrNull() ?: return null
        // Other packages of a workspace share its root lockfile, which resolves jaspr for them too.
        if (!JasprTooling.declaresJasprDependency(pubspecText)) return null
        val packageDir = pubspec.parent

        val lockfile = generateSequence(packageDir) { it.parent }
            .take(JasprTooling.MAX_LOCKFILE_SEARCH_LEVELS + 1)
            .firstNotNullOfOrNull { it.findChild("pubspec.lock") }
        if (lockfile != null) {
            dependencies += lockfile
            runCatching { tooling.parseJasprVersionFromLockfile(VfsUtilCore.loadText(lockfile)) }
                .getOrNull()?.let { return Resolved(it, lockfile.name, packageDir) }
        }
        return tooling.parseFrameworkVersionFromPubspec(pubspecText)?.let { Resolved(it, pubspec.name, packageDir) }
    }
}
