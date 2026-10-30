package com.github.eladrimonos.jasprintellij.editor

import com.github.eladrimonos.jasprintellij.editor.guides.JasprUiGuide
import com.github.eladrimonos.jasprintellij.editor.guides.JasprUiGuides
import com.github.eladrimonos.jasprintellij.settings.JasprSettings
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
                "4:6 -> li([.text(item)]),",
            ),
            describe(file, JasprUiGuides.compute(file)),
        )
    }

    fun testCollectionForBranchesToItsBody() {
        val file = jasprFile(
            """
            Component nav(String activePath) {
              return header([
                nav([
                  for (var route in [
                    (label: 'Home', path: '/'),
                  ])
                    div(classes: 'item', [
                      Link(to: route.path),
                    ]),
                ]),
              ]);
            }
            """
        )
        assertEquals(
            listOf(
                "1:2 -> nav([",
                "2:4 -> for (var route in [",
                "3:6 -> div(classes: 'item', [",
                "6:8 -> Link(to: route.path),",
            ),
            describe(file, JasprUiGuides.compute(file)),
        )
    }

    fun testCollectionIfBranchesToThenAndElse() {
        val file = jasprFile(
            """
            Component status(bool ok) {
              return div([
                if (ok)
                  span([.text('ok')])
                else
                  span([.text('error')]),
              ]);
            }
            """
        )
        assertEquals(
            listOf(
                "1:2 -> if (ok)",
                "2:4 -> span([.text('ok')]) | span([.text('error')]),",
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
}
