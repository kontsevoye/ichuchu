package io.github.kontsevoye.ichuchu.ui.screens.Settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.kontsevoye.ichuchu.ui.components.IchuButton
import io.github.kontsevoye.ichuchu.ui.components.IchuButtonVariant
import io.github.kontsevoye.ichuchu.ui.components.IchuSwitch
import io.github.kontsevoye.ichuchu.ui.components.IchuText
import io.github.kontsevoye.ichuchu.ui.screens.Keys.KeysContent
import io.github.kontsevoye.ichuchu.ui.screens.Keys.KeysViewModel
import io.github.kontsevoye.ichuchu.ui.screens.Terminal.TerminalTabMode
import io.github.kontsevoye.ichuchu.ui.terminal.TerminalCustomKeyGroup
import io.github.kontsevoye.ichuchu.ui.theme.IchuColors
import io.github.kontsevoye.ichuchu.ui.theme.IchuTypography
import io.github.kontsevoye.ichuchu.ui.theme.ThemeMode

enum class SettingsCategory(val label: String) {
    General("general"),
    Terminal("terminal"),
    Keys("keys"),
}

@Composable
fun SettingsScreen(
    currentTheme: String,
    currentFont: String,
    appLockEnabled: Boolean,
    requireAuthOnConnect: Boolean,
    localShellEnabled: Boolean,
    keepScreenAwake: Boolean,
    hideScreenContents: Boolean,
    disableAutocorrect: Boolean,
    currentAccessoryLayoutIds: List<String>,
    accessoryBarSingleRow: Boolean,
    currentTerminalCustomKeyGroups: List<TerminalCustomKeyGroup>,
    showCustomActionsFab: Boolean,
    onShowCustomActionsFabChanged: (Boolean) -> Unit,
    builtinShortcuts: Map<String, String> = emptyMap(),
    onBuiltinShortcutsChanged: (Map<String, String>) -> Unit = {},
    currentTabMode: TerminalTabMode = TerminalTabMode.Classic,
    onTabModeChanged: (TerminalTabMode) -> Unit = {},
    themeMode: ThemeMode,
    lightThemeName: String,
    onThemeSelected: (String) -> Unit,
    onThemeModeChanged: (ThemeMode) -> Unit,
    onLightThemeSelected: (String) -> Unit,
    onFontSelected: (String) -> Unit,
    onAppLockEnabledChanged: (Boolean) -> Unit,
    onRequireAuthOnConnectChanged: (Boolean) -> Unit,
    onLocalShellEnabledChanged: (Boolean) -> Unit,
    onKeepScreenAwakeChanged: (Boolean) -> Unit,
    onHideScreenContentsChanged: (Boolean) -> Unit,
    onDisableAutocorrectChanged: (Boolean) -> Unit,
    onAccessoryLayoutChanged: (List<String>) -> Unit,
    onAccessoryBarSingleRowChanged: (Boolean) -> Unit,
    currentTerminalFontSize: Float = 14f,
    onTerminalFontSizeChanged: (Float) -> Unit = {},
    onTerminalCustomActionsChanged: (List<TerminalCustomKeyGroup>) -> Unit,
    backupViewModel: SettingsBackupViewModel? = null,
    keysViewModel: KeysViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = IchuColors.current
    val typography = IchuTypography.current
    var selectedCategory by remember { mutableStateOf(SettingsCategory.General) }
    var showAccessoryEditor by remember { mutableStateOf(false) }
    var showCustomActionEditor by remember { mutableStateOf(false) }
    var showIchuchuCommandsEditor by remember { mutableStateOf(false) }
    var showBackupSheet by remember { mutableStateOf(false) }

    BackHandler(enabled = true) {
        when {
            showBackupSheet -> {
                backupViewModel?.dismissSheet()
                showBackupSheet = false
            }
            showCustomActionEditor -> showCustomActionEditor = false
            showIchuchuCommandsEditor -> showIchuchuCommandsEditor = false
            showAccessoryEditor -> showAccessoryEditor = false
            else -> onBack()
        }
    }

    Box(modifier = modifier.fillMaxSize().background(colors.background)) {
        LazyColumn(
            modifier =
                Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp)
                    .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IchuText("$ ", style = typography.headline, color = colors.textMuted)
                        IchuText("settings", style = typography.headline)
                    }
                    IchuButton(
                        onClick = onBack,
                        variant = IchuButtonVariant.Ghost,
                        bracketed = true,
                        borderColor = colors.textMuted,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        IchuText("x", style = typography.label, color = colors.textMuted)
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsCategory.entries.forEach { category ->
                        val isSelected = category == selectedCategory
                        IchuButton(
                            onClick = { selectedCategory = category },
                            variant = IchuButtonVariant.Outlined,
                            borderColor = if (isSelected) colors.accent else colors.border,
                            backgroundColor =
                                if (isSelected) colors.accent.copy(alpha = 0.12f) else null,
                            modifier = Modifier.weight(1f),
                        ) {
                            IchuText(
                                category.label,
                                style = typography.label,
                                color = if (isSelected) colors.accent else colors.textSecondary,
                            )
                        }
                    }
                }
            }

            item {
                when (selectedCategory) {
                    SettingsCategory.General ->
                        GeneralSettings(
                            currentTheme = currentTheme,
                            currentFont = currentFont,
                            onThemeSelected = onThemeSelected,
                            onThemeModeChanged = onThemeModeChanged,
                            themeMode = themeMode,
                            lightThemeName = lightThemeName,
                            onLightThemeSelected = onLightThemeSelected,
                            onFontSelected = onFontSelected,
                            appLockEnabled = appLockEnabled,
                            requireAuthOnConnect = requireAuthOnConnect,
                            onAppLockEnabledChanged = onAppLockEnabledChanged,
                            onRequireAuthOnConnectChanged = onRequireAuthOnConnectChanged,
                            onOpenBackup = { showBackupSheet = true },
                        )
                    SettingsCategory.Terminal ->
                        TerminalSettings(
                            currentAccessoryLayoutIds = currentAccessoryLayoutIds,
                            onEditAccessoryLayout = { showAccessoryEditor = true },
                            accessoryBarSingleRow = accessoryBarSingleRow,
                            onAccessoryBarSingleRowChanged = onAccessoryBarSingleRowChanged,
                            currentTerminalFontSize = currentTerminalFontSize,
                            onTerminalFontSizeChanged = onTerminalFontSizeChanged,
                            currentTerminalCustomKeyGroups = currentTerminalCustomKeyGroups,
                            onEditCustomActions = { showCustomActionEditor = true },
                            showCustomActionsFab = showCustomActionsFab,
                            onShowCustomActionsFabChanged = onShowCustomActionsFabChanged,
                            builtinShortcuts = builtinShortcuts,
                            onEditIchuchuCommands = { showIchuchuCommandsEditor = true },
                            currentTabMode = currentTabMode,
                            onTabModeChanged = onTabModeChanged,
                            localShellEnabled = localShellEnabled,
                            onLocalShellEnabledChanged = onLocalShellEnabledChanged,
                            keepScreenAwake = keepScreenAwake,
                            onKeepScreenAwakeChanged = onKeepScreenAwakeChanged,
                            hideScreenContents = hideScreenContents,
                            onHideScreenContentsChanged = onHideScreenContentsChanged,
                            disableAutocorrect = disableAutocorrect,
                            onDisableAutocorrectChanged = onDisableAutocorrectChanged,
                        )
                    SettingsCategory.Keys -> KeysContent(vm = keysViewModel)
                }
            }
        }

        AccessoryLayoutEditorSheet(
            visible = showAccessoryEditor,
            selectedIds = currentAccessoryLayoutIds,
            onSave = {
                onAccessoryLayoutChanged(it)
                showAccessoryEditor = false
            },
            onDismiss = { showAccessoryEditor = false },
        )

        TerminalCustomActionsEditorSheet(
            visible = showCustomActionEditor,
            initialGroups = currentTerminalCustomKeyGroups,
            onSave = onTerminalCustomActionsChanged,
            onDismiss = { showCustomActionEditor = false },
        )

        IchuchuCommandsEditorSheet(
            visible = showIchuchuCommandsEditor,
            builtinShortcuts = builtinShortcuts,
            onSave = {
                onBuiltinShortcutsChanged(it)
                showIchuchuCommandsEditor = false
            },
            onDismiss = { showIchuchuCommandsEditor = false },
        )

        if (backupViewModel != null) {
            SshBackupSheet(
                visible = showBackupSheet,
                viewModel = backupViewModel,
                onDismiss = { showBackupSheet = false },
            )
        }
    }
}

@Composable
private fun GeneralSettings(
    currentTheme: String,
    currentFont: String,
    onThemeSelected: (String) -> Unit,
    onThemeModeChanged: (ThemeMode) -> Unit,
    themeMode: ThemeMode,
    lightThemeName: String,
    onLightThemeSelected: (String) -> Unit,
    onFontSelected: (String) -> Unit,
    appLockEnabled: Boolean,
    requireAuthOnConnect: Boolean,
    onAppLockEnabledChanged: (Boolean) -> Unit,
    onRequireAuthOnConnectChanged: (Boolean) -> Unit,
    onOpenBackup: () -> Unit = {},
) {
    val typography = IchuTypography.current
    val colors = IchuColors.current
    ThemeSelectorSection(
        darkThemeName = currentTheme,
        onDarkThemeSelected = onThemeSelected,
        lightThemeName = lightThemeName,
        onLightThemeSelected = onLightThemeSelected,
        themeMode = themeMode,
        onThemeModeChanged = onThemeModeChanged,
    )
    Spacer(modifier = Modifier.height(16.dp))
    FontSelectorSection(currentFont = currentFont, onFontSelected = onFontSelected)
    Spacer(modifier = Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        IchuText("── ", style = typography.labelSmall, color = colors.textMuted)
        IchuText("SECURITY", style = typography.labelSmall, color = colors.textMuted)
        IchuText(" ", style = typography.labelSmall, color = colors.textMuted)
        Box(modifier = Modifier.height(1.dp).background(colors.textMuted).fillMaxWidth())
    }
    Spacer(modifier = Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IchuText("lock app on open", style = typography.label)
        IchuSwitch(checked = appLockEnabled, onCheckedChange = onAppLockEnabledChanged)
    }
    Spacer(modifier = Modifier.height(20.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IchuText("verify before server connect", style = typography.label)
        IchuSwitch(checked = requireAuthOnConnect, onCheckedChange = onRequireAuthOnConnectChanged)
    }
    Spacer(modifier = Modifier.height(4.dp))
    IchuText(
        "use biometrics or device PIN/pattern.",
        style = typography.bodySmall,
        color = colors.textMuted,
    )
    Spacer(modifier = Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IchuText("ssh hosts & keys backup", style = typography.label)
        IchuButton(
            onClick = onOpenBackup,
            variant = IchuButtonVariant.Outlined,
            bracketed = true,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        ) {
            IchuText("manage", style = typography.label)
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
    IchuText("encrypted export/import.", style = typography.bodySmall, color = colors.textMuted)
    UpdatesSection()
}
