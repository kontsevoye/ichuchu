package io.github.kontsevoye.ichuchu

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.kontsevoye.ichuchu.data.repository.SettingsRepository
import io.github.kontsevoye.ichuchu.service.update.UpdateNotifications
import io.github.kontsevoye.ichuchu.service.update.UpdateServices
import io.github.kontsevoye.ichuchu.ui.ApplicationNavController
import io.github.kontsevoye.ichuchu.ui.theme.GhosttyThemeRegistry
import io.github.kontsevoye.ichuchu.ui.theme.IchuColors
import io.github.kontsevoye.ichuchu.ui.theme.IchuTheme
import io.github.kontsevoye.ichuchu.ui.theme.resolveActiveThemeName
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val updates = UpdateServices.get(this)
        if (intent.getBooleanExtra(UpdateNotifications.OPEN_UPDATES, false))
            updates.requestOpenSettings()
        val settings = SettingsRepository.getInstance(this)
        lifecycleScope.launch {
            settings.hideScreenContents.collect { hideScreenContents ->
                if (hideScreenContents) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(0x00000000),
            navigationBarStyle = SystemBarStyle.dark(0x00000000),
        )
        setContent { AppRoot() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(UpdateNotifications.OPEN_UPDATES, false))
            UpdateServices.get(this).requestOpenSettings()
    }
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    GhosttyThemeRegistry.init(context)
    val settings = SettingsRepository.getInstance(context)
    val fontName by settings.fontName.collectAsStateWithLifecycle()
    val themeName by settings.themeName.collectAsStateWithLifecycle()
    val themeMode by settings.themeMode.collectAsStateWithLifecycle()
    val lightThemeName by settings.lightThemeName.collectAsStateWithLifecycle()
    val resolvedThemeName =
        resolveActiveThemeName(
            themeMode = themeMode,
            darkThemeName = themeName,
            lightThemeName = lightThemeName,
        )

    IchuTheme(themeName = resolvedThemeName, fontName = fontName) {
        Box(modifier = Modifier.fillMaxSize().background(IchuColors.current.background)) {
            ApplicationNavController()
        }
    }
}
