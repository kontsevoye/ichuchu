package io.github.kontsevoye.ichuchu.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.kontsevoye.ichuchu.model.HostProfile
import io.github.kontsevoye.ichuchu.ui.screens.ServerList.HostCard
import io.github.kontsevoye.ichuchu.ui.theme.IchuTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HostCardLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun jumpRouteKeepsWholeActionButtons() = checkCard(360, 1f, false)

    @Test fun narrowLargeTextKeepsWholeDisconnectButton() = checkCard(280, 2f, true)

    @Test fun narrowMediumTextKeepsWholeConnectButton() = checkCard(280, 1.5f, false)

    @Test fun directHostKeepsWholeActionButtons() = checkCard(280, 2f, false, false)

    private fun checkCard(width: Int, fontScale: Float, connected: Boolean, jump: Boolean = true) {
        var editClicks = 0
        var connectClicks = 0
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                IchuTheme {
                    Box(Modifier.width(width.dp)) {
                        HostCard(
                            host =
                                HostProfile(
                                    id = 1,
                                    name = "Long target server name",
                                    host = "target.internal",
                                    username = "fixture",
                                    jumpHostIds = if (jump) listOf(2, 3, 4) else emptyList(),
                                ),
                            routeLabel = "a very long gateway → another gateway → third gateway",
                            isConnected = connected,
                            isSelected = false,
                            onLongPress = {},
                            onCancelSelection = {},
                            onDeleteSelection = {},
                            onEdit = { editClicks++ },
                            onConnect = { connectClicks++ },
                            onDisconnect = { connectClicks++ },
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("host_edit_1").assertHeightIsAtLeast(36.dp).performClick()
        compose.onNodeWithTag("host_connect_1").assertHeightIsAtLeast(36.dp).performClick()
        assertEquals(1, editClicks)
        assertEquals(1, connectClicks)
        compose
            .onRoot()
            .captureRoboImage("build/outputs/roborazzi/host-$width-$fontScale-$connected-$jump.png")
    }
}
