package com.github.eladrimonos.jasprintellij.editor.codevision

import com.github.eladrimonos.jasprintellij.JasprBundle

class JasprServerScopeProvider : BaseJasprScopeProvider() {

    override val id: String = "jaspr.component.scopes.server"
    override val name: String = JasprBundle.message("jaspr.component.scopes.server.name")
}