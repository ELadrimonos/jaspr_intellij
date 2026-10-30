package com.github.eladrimonos.jasprintellij.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(name = "JasprSettings", storages = [Storage("jaspr.xml")])
class JasprSettings : SimplePersistentStateComponent<JasprSettings.SettingsState>(SettingsState()) {

    class SettingsState : BaseState() {
        var showUiGuides by property(true)
    }

    var showUiGuides: Boolean
        get() = state.showUiGuides
        set(value) {
            state.showUiGuides = value
        }

    companion object {
        fun getInstance(): JasprSettings = service()
    }
}
