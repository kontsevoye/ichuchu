package io.github.kontsevoye.ichuchu

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kontsevoye.ichuchu.ui.screens.agents.AgentMarkdown
import io.github.kontsevoye.ichuchu.ui.theme.GhosttyThemeRegistry
import io.github.kontsevoye.ichuchu.ui.theme.IchuTheme
import org.junit.Rule
import org.junit.Test

class AgentMemoryCitationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun assistantMemoryReferencesAreCompactAndExpandable() {
        GhosttyThemeRegistry.init(InstrumentationRegistry.getInstrumentation().targetContext)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides
                    Density(
                        density.density,
                        InstrumentationRegistry.getArguments()
                            .getString("agentFontScale", "1.0")
                            .toFloat(),
                    )
            ) {
                IchuTheme {
                    AgentMarkdown(
                        """Fixed the session.
<oai-mem-citation>
<citation_entries>
MEMORY.md:116-119|note=[guarded sending and release verification workflow]
</citation_entries>
<rollout_ids>
01a1a250-2f3b-7e02-b336-177b8651f373
</rollout_ids>
</oai-mem-citation>""",
                        memoryCitations = true,
                    )
                }
            }
        }
        compose.onNodeWithText("<oai-mem-citation>", substring = true).assertDoesNotExist()
        compose.onNodeWithText("MEMORY.md:116–119").assertDoesNotExist()
        capture("memory-collapsed")
        compose.onNodeWithContentDescription("Memory sources · 1").performClick()
        compose.onNodeWithText("MEMORY.md:116–119").assertIsDisplayed()
        compose
            .onNodeWithText("guarded sending and release verification workflow")
            .assertIsDisplayed()
        capture("memory-expanded")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = java.io.File(instrumentation.targetContext.cacheDir, "$name.png")
        requireNotNull(instrumentation.uiAutomation.takeScreenshot()).let { bitmap ->
            file.outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
