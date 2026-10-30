package com.github.eladrimonos.jasprintellij.editor

import com.github.eladrimonos.jasprintellij.editor.guides.JasprIndentsPassFilter
import com.github.eladrimonos.jasprintellij.editor.guides.JasprUiGuide
import com.github.eladrimonos.jasprintellij.editor.guides.JasprUiGuides
import com.github.eladrimonos.jasprintellij.settings.JasprSettings
import com.intellij.openapi.editor.IndentGuideDescriptor
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class JasprUiGuidesTest : BasePlatformTestCase() {

    private fun jasprFile(code: String): PsiFile {
        myFixture.addFileToProject("app/pubspec.yaml", "name: app\ndependencies:\n  jaspr: ^0.24.0\n")
        return myFixture.addFileToProject("app/lib/app.dart", code.trimIndent())
    }

    /** Each guide as "line:indent -> child texts". */
    private fun describe(file: PsiFile, guides: List<JasprUiGuide>): List<String> = guides.map { guide ->
        val children = guide.childOffsets.map { offset ->
            file.text.substring(offset).substringBefore('\n').trim()
        }
        "${guide.line}:${guide.indent} -> ${children.joinToString(" | ")}"
    }

    fun testGuidesFollowNestedElements() {
        val file = jasprFile(
            """
            class App extends StatelessComponent {
              @override
              Component build(BuildContext context) {
                return div(classes: 'main', [
                  h1([.text('Title')]),
                  section([
                    p([.text('a')]),
                    p([.text('b')]),
                  ]),
                ]);
              }
            }
            """
        )
        assertEquals(
            listOf(
                "3:4 -> h1([.text('Title')]), | section([",
                "5:6 -> p([.text('a')]), | p([.text('b')]),",
            ),
            describe(file, JasprUiGuides.compute(file)),
        )
    }

    fun testCollectionElementsAndNamedChildrenAreChildren() {
        val file = jasprFile(
            """
            Component list(List<String> items, bool header) {
              return Document(
                body: ul([
                  if (header) li([.text('Header')]),
                  for (final item in items)
                    li([.text(item)]),
                  ...extra,
                ]),
              );
            }
            """
        )
        assertEquals(
            listOf(
                "1:2 -> body: ul([",
                "2:4 -> if (header) li([.text('Header')]), | for (final item in items) | ...extra,",
            ),
            describe(file, JasprUiGuides.compute(file)),
        )
    }

    fun testChildrenOnTheSameLineGetNoGuide() {
        val file = jasprFile(
            """
            Component title() => h1([.text('a'), .text('b')]);
            """
        )
        assertEmpty(JasprUiGuides.compute(file))
    }

    fun testListsOutsideComponentCodeAndCssRulesGetNoGuide() {
        val file = jasprFile(
            """
            Future<void> load() async {
              await Future.wait([
                fetchA(),
                fetchB(),
              ]);
            }

            class App extends StatelessComponent {
              @css
              static List<StyleRule> get styles => [
                css('.a', [
                  css('&:hover').styles(),
                ]),
              ];

              @override
              Component build(BuildContext context) {
                void onClick() {
                  Future.wait([
                    fetchA(),
                  ]);
                }
                return div([]);
              }
            }
            """
        )
        assertEmpty(JasprUiGuides.compute(file))
    }

    fun testNoGuidesOutsideJasprPackagesOrWhenTurnedOff() {
        val code = "Component a() => div([\n  p([]),\n]);\n"
        val plain = myFixture.addFileToProject("plain/lib/a.dart", code)
        assertEmpty(JasprUiGuides.forFile(plain))

        val jaspr = jasprFile(code)
        assertSize(1, JasprUiGuides.forFile(jaspr))

        val settings = JasprSettings.getInstance()
        settings.showUiGuides = false
        try {
            assertEmpty(JasprUiGuides.forFile(jaspr))
        } finally {
            settings.showUiGuides = true
        }
    }

    fun testIndentGuidesInTheGuideColumnAreHidden() {
        val filter = JasprIndentsPassFilter()
        val guide = JasprUiGuide(startOffset = 0, endOffset = 0, line = 3, indent = 4, childOffsets = listOf(0), lastChildLine = 8)
        assertTrue(filter.overlaps(guide, IndentGuideDescriptor(4, 3, 10)))
        assertFalse(filter.overlaps(guide, IndentGuideDescriptor(6, 4, 8)))
        assertFalse(filter.overlaps(guide, IndentGuideDescriptor(4, 9, 12)))
    }
}
