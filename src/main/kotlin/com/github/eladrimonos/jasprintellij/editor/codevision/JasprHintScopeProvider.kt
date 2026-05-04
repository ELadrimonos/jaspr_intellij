package com.github.eladrimonos.jasprintellij.editor.codevision

import com.github.eladrimonos.jasprintellij.JasprBundle
import com.github.eladrimonos.jasprintellij.JasprLegacy

@JasprLegacy("Use new component scope implementation", "0.23.0")
class JasprHintScopeProvider : BaseJasprScopeProvider() {

    override val id: String = "jaspr.component.scopes.hint"
    override val name: String = JasprBundle.message("jaspr.component.scopes.hint.name")
}