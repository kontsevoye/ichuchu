package io.github.kontsevoye.ichuchu.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.kontsevoye.ichuchu.data.repository.SettingsRepository
import io.github.kontsevoye.ichuchu.service.update.UpdateServices
import io.github.kontsevoye.ichuchu.ui.screens.AddServer.AddServerScreen
import io.github.kontsevoye.ichuchu.ui.screens.AddServer.AddServerViewModel
import io.github.kontsevoye.ichuchu.ui.screens.Keys.KeysViewModel
import io.github.kontsevoye.ichuchu.ui.screens.ServerList.ServerListScreen
import io.github.kontsevoye.ichuchu.ui.screens.ServerList.ServerListViewModel
import io.github.kontsevoye.ichuchu.ui.screens.Settings.SettingsBackupViewModel
import io.github.kontsevoye.ichuchu.ui.screens.Settings.SettingsScreen
import io.github.kontsevoye.ichuchu.ui.screens.Settings.UpdateInteractionHost
import io.github.kontsevoye.ichuchu.ui.screens.Terminal.TerminalScreen
import io.github.kontsevoye.ichuchu.ui.screens.Terminal.TerminalViewModel
import io.github.kontsevoye.ichuchu.ui.security.AppLockGate
import io.github.kontsevoye.ichuchu.ui.security.VerificationResult
import io.github.kontsevoye.ichuchu.ui.security.requireUserVerification

@Composable
fun ApplicationNavController() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val settings = SettingsRepository.getInstance(application)
    val lockEnabled by settings.appLockEnabled.collectAsStateWithLifecycle()
    AppLockGate(
        enabled = lockEnabled,
        verify = { callback ->
            requireUserVerification(context, "Unlock Ichuchu", "Authenticate to continue", callback)
        },
    ) {
        UpdateInteractionHost()
        ProtectedNavigation(navController)
    }
}

@Composable
private fun ProtectedNavigation(navController: NavHostController) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val updates = UpdateServices.get(application)
    val openUpdates by updates.openSettings.collectAsStateWithLifecycle()
    LaunchedEffect(openUpdates) {
        if (openUpdates) {
            navController.navigate("settings") { launchSingleTop = true }
            updates.consumeOpenSettings()
        }
    }
    NavHost(navController = navController, startDestination = "servers") {
        composable("servers") {
            val vm: ServerListViewModel =
                viewModel(factory = ServerListViewModel.factory(application))
            val settingsRepo = SettingsRepository.getInstance(application)
            val requireAuthOnConnect by
                settingsRepo.requireAuthOnConnect.collectAsStateWithLifecycle()
            val localShellEnabled by settingsRepo.localShellEnabled.collectAsStateWithLifecycle()
            val hosts by vm.hosts.collectAsStateWithLifecycle()
            val searchQuery by vm.search.collectAsStateWithLifecycle()
            ServerListScreen(
                hosts = hosts,
                searchQuery = searchQuery,
                onSearchChange = vm::updateSearchQuery,
                onAddServer = { navController.navigate("servers/add") },
                localShellEnabled = localShellEnabled,
                onOpenLocalShell = localShell@{
                        if (!localShellEnabled) return@localShell
                        if (!requireAuthOnConnect) {
                            navController.navigate("terminal/local")
                        } else {
                            requireUserVerification(
                                context = context,
                                title = "Verify to open local shell",
                                subtitle = "Authenticate to open this device shell",
                            ) { result ->
                                if (result == VerificationResult.Success) {
                                    navController.navigate("terminal/local")
                                }
                            }
                        }
                    },
                onEditServer = { id -> navController.navigate("servers/edit/$id") },
                onConnectServer = { id ->
                    val host = hosts.firstOrNull { it.id == id }
                    val hostRequiresAuth = host?.requireAuthOnConnect == true
                    val mustVerify = requireAuthOnConnect || hostRequiresAuth
                    if (!mustVerify) {
                        navController.navigate("terminal/$id")
                    } else {
                        requireUserVerification(
                            context = context,
                            title = "Verify to connect",
                            subtitle = "Authenticate to open this server session",
                        ) { result ->
                            if (result == VerificationResult.Success) {
                                navController.navigate("terminal/$id")
                            }
                        }
                    }
                },
                onDeleteServer = vm::deleteServer,
                onOpenSettings = { navController.navigate("settings") },
            )
        }
        composable("settings") {
            val settingsRepo = SettingsRepository.getInstance(application)
            val backupViewModel: SettingsBackupViewModel =
                viewModel(factory = SettingsBackupViewModel.factory(application))
            val keysViewModel: KeysViewModel =
                viewModel(factory = KeysViewModel.factory(application))
            val themeName by settingsRepo.themeName.collectAsStateWithLifecycle()
            val fontName by settingsRepo.fontName.collectAsStateWithLifecycle()
            val appLockEnabled by settingsRepo.appLockEnabled.collectAsStateWithLifecycle()
            val requireAuthOnConnect by
                settingsRepo.requireAuthOnConnect.collectAsStateWithLifecycle()
            val accessoryLayoutIds by settingsRepo.accessoryLayoutIds.collectAsStateWithLifecycle()
            val accessoryBarSingleRow by
                settingsRepo.accessoryBarSingleRow.collectAsStateWithLifecycle()
            val customKeyGroups by
                settingsRepo.terminalCustomKeyGroups.collectAsStateWithLifecycle()
            val showCustomActionsFab by
                settingsRepo.showCustomActionsFab.collectAsStateWithLifecycle()
            val builtinShortcuts by settingsRepo.builtinShortcuts.collectAsStateWithLifecycle()
            val tabMode by settingsRepo.terminalTabMode.collectAsStateWithLifecycle()
            val localShellEnabled by settingsRepo.localShellEnabled.collectAsStateWithLifecycle()
            val keepScreenAwake by settingsRepo.keepScreenAwake.collectAsStateWithLifecycle()
            val hideScreenContents by settingsRepo.hideScreenContents.collectAsStateWithLifecycle()
            val disableAutocorrect by settingsRepo.disableAutocorrect.collectAsStateWithLifecycle()
            val themeMode by settingsRepo.themeMode.collectAsStateWithLifecycle()
            val terminalFontSize by settingsRepo.terminalFontSize.collectAsStateWithLifecycle()
            val lightThemeName by settingsRepo.lightThemeName.collectAsStateWithLifecycle()
            SettingsScreen(
                currentTheme = themeName,
                currentFont = fontName,
                appLockEnabled = appLockEnabled,
                requireAuthOnConnect = requireAuthOnConnect,
                localShellEnabled = localShellEnabled,
                keepScreenAwake = keepScreenAwake,
                hideScreenContents = hideScreenContents,
                disableAutocorrect = disableAutocorrect,
                currentAccessoryLayoutIds = accessoryLayoutIds,
                accessoryBarSingleRow = accessoryBarSingleRow,
                currentTerminalCustomKeyGroups = customKeyGroups,
                showCustomActionsFab = showCustomActionsFab,
                onShowCustomActionsFabChanged = settingsRepo::setShowCustomActionsFab,
                builtinShortcuts = builtinShortcuts,
                onBuiltinShortcutsChanged = settingsRepo::setBuiltinShortcuts,
                currentTabMode = tabMode,
                onTabModeChanged = settingsRepo::setTerminalTabMode,
                themeMode = themeMode,
                lightThemeName = lightThemeName,
                onThemeSelected = settingsRepo::setTheme,
                onThemeModeChanged = settingsRepo::setThemeMode,
                onLightThemeSelected = settingsRepo::setLightTheme,
                onFontSelected = settingsRepo::setFont,
                onAppLockEnabledChanged = settingsRepo::setAppLockEnabled,
                onRequireAuthOnConnectChanged = settingsRepo::setRequireAuthOnConnect,
                onLocalShellEnabledChanged = settingsRepo::setLocalShellEnabled,
                onKeepScreenAwakeChanged = settingsRepo::setKeepScreenAwake,
                onHideScreenContentsChanged = settingsRepo::setHideScreenContents,
                onDisableAutocorrectChanged = settingsRepo::setDisableAutocorrect,
                onAccessoryLayoutChanged = settingsRepo::setAccessoryLayoutIds,
                onAccessoryBarSingleRowChanged = settingsRepo::setAccessoryBarSingleRow,
                currentTerminalFontSize = terminalFontSize,
                onTerminalFontSizeChanged = settingsRepo::setTerminalFontSize,
                onTerminalCustomActionsChanged = settingsRepo::setTerminalCustomKeyGroups,
                backupViewModel = backupViewModel,
                keysViewModel = keysViewModel,
                onBack = {
                    val currentRoute = navController.currentBackStackEntry?.destination?.route
                    if (currentRoute == "settings") {
                        navController.popBackStack()
                    }
                },
            )
        }
        composable("servers/add") {
            val vm: AddServerViewModel =
                viewModel(factory = AddServerViewModel.factory(application, null))
            AddServerScreen(vm = vm, onBack = { navController.popBackStack() })
        }
        composable(
            route = "servers/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("id")
            val vm: AddServerViewModel =
                viewModel(factory = AddServerViewModel.factory(application, id))
            AddServerScreen(vm = vm, onBack = { navController.popBackStack() })
        }
        composable("terminal/local") {
            val settingsRepo = SettingsRepository.getInstance(application)
            val localShellEnabled by settingsRepo.localShellEnabled.collectAsStateWithLifecycle()
            if (localShellEnabled) {
                val vm: TerminalViewModel =
                    viewModel(factory = TerminalViewModel.factory(application))
                TerminalScreen(
                    vm = vm,
                    hostId = null,
                    openLocalShell = true,
                    onOpenSettings = { navController.navigate("settings") },
                    onBack = { navController.popBackStack() },
                )
            } else {
                LaunchedEffect(Unit) {
                    val popped = navController.popBackStack("servers", inclusive = false)
                    if (!popped) {
                        navController.navigate("servers") { launchSingleTop = true }
                    }
                }
            }
        }
        composable(
            route = "terminal/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("id")
            val vm: TerminalViewModel = viewModel(factory = TerminalViewModel.factory(application))
            TerminalScreen(
                vm = vm,
                hostId = id,
                onOpenSettings = { navController.navigate("settings") },
                onBack = { navController.popBackStack() },
            )
        }
    }
}
