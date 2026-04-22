package com.github.eladrimonos.jasprintellij.editor.codevision

import com.github.eladrimonos.jasprintellij.JasprBundle
import com.github.eladrimonos.jasprintellij.JasprLegacy

@JasprLegacy("Use new component scope implementation", "0.23.0")
class JasprClientScopeProvider : BaseJasprScopeProvider() {

    override val id: String = "jaspr.component.scopes.client"
    override val name: String = JasprBundle.message("jaspr.component.scopes.client.name")
}