package com.github.eladrimonos.jasprintellij.template

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class JasprClassMemberTemplateContextTypeTest : BasePlatformTestCase() {

    private val context = JasprClassMemberTemplateContextType()

    private fun isInContextAtCaret(code: String): Boolean {
        myFixture.configureByText("app.dart", code)
        val element = myFixture.file.findElementAt(myFixture.caretOffset - 1)!!
        return context.isInContext(element)
    }

    fun testClassBodyIsInContext() {
        assertTrue(isInContextAtCaret("class App {\n  x<caret>\n}\n"))
    }

    fun testMethodBodyIsNotInContext() {
        assertFalse(isInContextAtCaret("class App {\n  void f() {\n    x<caret>\n  }\n}\n"))
    }

    fun testTopLevelIsNotInContext() {
        assertFalse(isInContextAtCaret("x<caret>\n"))
    }
}
