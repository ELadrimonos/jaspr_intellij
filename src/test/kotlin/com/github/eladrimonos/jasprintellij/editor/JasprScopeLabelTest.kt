package com.github.eladrimonos.jasprintellij.editor

import com.github.eladrimonos.jasprintellij.editor.codevision.BaseJasprScopeProvider
import com.github.eladrimonos.jasprintellij.services.JasprToolingDaemonService.ScopeTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class JasprScopeLabelTest {

    private fun root(app: String?) = ScopeTarget(path = "/ws/apps/$app/lib/app.dart", line = 1, character = 0, app = app)

    @Test
    fun `roots of a single app keep the plain label`() {
        assertEquals("Client Scope", BaseJasprScopeProvider.scopeLabel("Client Scope", listOf(root(null), root(null))))
    }

    @Test
    fun `roots of a shared package name each app once`() {
        assertEquals(
            "Client Scope · web, admin",
            BaseJasprScopeProvider.scopeLabel("Client Scope", listOf(root("web"), root("admin"), root("web"))),
        )
    }
}
