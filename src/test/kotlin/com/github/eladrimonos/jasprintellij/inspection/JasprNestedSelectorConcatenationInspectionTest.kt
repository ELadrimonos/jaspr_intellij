package com.github.eladrimonos.jasprintellij.inspection

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class JasprNestedSelectorConcatenationInspectionTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(JasprNestedSelectorConcatenationInspection())
    }

    private fun useJaspr(version: String) {
        myFixture.addFileToProject("pubspec.yaml", "name: app\ndependencies:\n  jaspr: ^0.23.0\n")
        myFixture.addFileToProject("pubspec.lock", "packages:\n  jaspr:\n    version: \"$version\"\n")
    }

    private fun problems(code: String): List<String> {
        myFixture.configureByText("styles.dart", code)
        return myFixture.doHighlighting()
            .filter { it.description?.contains("Native CSS nesting") == true }
            .map { it.text }
    }

    fun testReportsConcatenationInNestedRule() {
        useJaspr("0.24.0-beta.0")
        val found = problems("final styles = [\n  css('.button', [\n    css('&-active').styles(),\n  ]),\n];\n")
        assertEquals(listOf("'&-active'"), found)
    }

    fun testIgnoresNativeNestingSelectors() {
        useJaspr("0.24.0")
        val found = problems(
            "final styles = [\n  css('.button', [\n    css('&:hover'),\n    css('&.primary'),\n    css('& p'),\n    css('&'),\n  ]),\n];\n"
        )
        assertEmpty(found)
    }

    fun testIgnoresTopLevelRule() {
        useJaspr("0.24.0")
        assertEmpty(problems("final styles = [\n  css('&-active'),\n];\n"))
    }

    fun testReportsInsideNestedAtRule() {
        useJaspr("0.24.0")
        val found = problems("final styles = [\n  css('.button', [\n    css.media(query, [css('&-wide')]),\n  ]),\n];\n")
        assertEquals(listOf("'&-wide'"), found)
    }

    fun testSilentBeforeNativeNesting() {
        useJaspr("0.23.4")
        assertEmpty(problems("final styles = [\n  css('.button', [\n    css('&-active'),\n  ]),\n];\n"))
    }

    fun testFallsBackToPubspecConstraintWithoutLockfile() {
        myFixture.addFileToProject("pubspec.yaml", "name: app\ndependencies:\n  jaspr: ^0.24.0\n")
        val found = problems("final styles = [\n  css('.button', [\n    css('&-active'),\n  ]),\n];\n")
        assertEquals(listOf("'&-active'"), found)
    }

    fun testFixMovesRuleNextToParent() {
        useJaspr("0.24.0")
        myFixture.configureByText(
            "styles.dart",
            "final styles = [\n  css('.button', [\n    css('&:hover').styles(),\n    css('&-act<caret>ive').styles(),\n  ]).styles(),\n];\n",
        )
        myFixture.launchAction(myFixture.findSingleIntention("Move out as css('.button-active')"))
        myFixture.checkResult(
            "final styles = [\n  css('.button', [\n    css('&:hover').styles(),\n  ]).styles(),\n  css('.button-active').styles(),\n];\n"
        )
    }

    fun testFixDropsEmptyChildList() {
        useJaspr("0.24.0")
        myFixture.configureByText(
            "styles.dart",
            "final styles = [\n  css('.button', [css('&-act<caret>ive')]),\n];\n",
        )
        myFixture.launchAction(myFixture.findSingleIntention("Move out as css('.button-active')"))
        myFixture.checkResult("final styles = [\n  css('.button'),\n  css('.button-active'),\n];\n")
    }

    fun testNoFixForSelectorListParent() {
        useJaspr("0.24.0")
        myFixture.configureByText(
            "styles.dart",
            "final styles = [\n  css('.a, .b', [css('&-act<caret>ive')]),\n];\n",
        )
        assertEmpty(myFixture.filterAvailableIntentions("Move out as"))
    }
}
