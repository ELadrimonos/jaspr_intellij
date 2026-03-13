package com.github.eladrimonos.jasprintellij.editor.codevision

import com.github.eladrimonos.jasprintellij.JasprBundle

class JasprHintScopeProvider : BaseJasprScopeProvider() {

    override val id: String = "jaspr.component.scopes.hint"
    override val name: String = JasprBundle.message("jaspr.component.scopes.hint.name")
}