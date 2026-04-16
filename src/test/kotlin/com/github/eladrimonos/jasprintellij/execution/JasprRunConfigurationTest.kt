package com.github.eladrimonos.jasprintellij.execution

import com.github.eladrimonos.jasprintellij.execution.runconfig.JasprConfigurationFactory
import com.github.eladrimonos.jasprintellij.execution.runconfig.JasprRunConfiguration
import com.github.eladrimonos.jasprintellij.execution.runconfig.JasprRunConfigurationType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jdom.Element

class JasprRunConfigurationTest : BasePlatformTestCase() {

    fun testSerialization() {
        val type = JasprRunConfigurationType()
        val factory = JasprConfigurationFactory(type)
        val config = JasprRunConfiguration(project, factory, "Test Config")

        // Set non-default values
        config.input = "lib/main.server.dart"
        config.port = "9090"
        config.verbose = true
        config.mode = "reload"
        config.dartDefine = "KEY=VALUE"

        // Write to XML
        val element = Element("configuration")
        config.writeExternal(element)

        // Create new config and read from XML
        val newConfig = JasprRunConfiguration(project, factory, "New Config")
        newConfig.readExternal(element)

        // Verify values
        assertEquals("lib/main.server.dart", newConfig.input)
        assertEquals("9090", newConfig.port)
        assertTrue(newConfig.verbose)
        assertEquals("reload", newConfig.mode)
        assertEquals("KEY=VALUE", newConfig.dartDefine)
    }

    fun testOptionsMapping() {
        val type = JasprRunConfigurationType()
        val factory = JasprConfigurationFactory(type)
        val config = JasprRunConfiguration(project, factory, "Test Config")

        config.input = "app.dart"
        assertEquals("app.dart", config.input)

        config.port = "1234"
        assertEquals("1234", config.port)
    }
}
