package io.github.kontsevoye.ichuchu.ui.screens.Settings

import android.Manifest
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kontsevoye.ichuchu.data.update.UpdatePhase
import io.github.kontsevoye.ichuchu.ui.components.IchuButton
import io.github.kontsevoye.ichuchu.ui.components.IchuButtonVariant
import io.github.kontsevoye.ichuchu.ui.components.IchuSwitch
import io.github.kontsevoye.ichuchu.ui.components.IchuText
import io.github.kontsevoye.ichuchu.ui.theme.IchuColors
import io.github.kontsevoye.ichuchu.ui.theme.IchuTypography
import java.text.DateFormat
import java.util.Date

@Composable
fun UpdatesSection() {
    val context = LocalContext.current
    val vm: SettingsUpdateViewModel =
        viewModel(
            factory = SettingsUpdateViewModel.factory(context.applicationContext as Application)
        )
    val updates = vm.updates
    val state by updates.state.collectAsStateWithLifecycle()
    val automatic by updates.automatic.collectAsStateWithLifecycle()
    val installed = updates.installed
    val typography = IchuTypography.current
    val colors = IchuColors.current
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.height(12.dp))
        IchuText("── UPDATES", style = typography.labelSmall, color = colors.textMuted)
        IchuText(
            "installed ${installed.versionName} (${installed.versionCode})",
            style = typography.label,
        )
        if (!updates.config.enabled) {
            IchuText(
                "In-app updates are disabled in this local build. Use an official signed GitHub release.",
                style = typography.bodySmall,
            )
            return@Column
        }
        IchuText(
            "source: ${updates.config.repository}",
            style = typography.bodySmall,
            color = colors.textMuted,
        )
        if (state.lastChecked > 0)
            IchuText(
                "last successful check: ${DateFormat.getDateTimeInstance().format(Date(state.lastChecked))}",
                style = typography.bodySmall,
            )
        val busy =
            state.phase in
                setOf(
                    UpdatePhase.Checking,
                    UpdatePhase.Downloading,
                    UpdatePhase.Validating,
                    UpdatePhase.Installing,
                    UpdatePhase.UserConfirmation,
                )
        when (state.phase) {
            UpdatePhase.Checking -> IchuText("checking for updates…")
            UpdatePhase.UpToDate -> IchuText("you are up to date")
            UpdatePhase.Installed -> IchuText("update installed")
            UpdatePhase.Downloading ->
                IchuText(
                    "downloaded ${state.received / 1024} / ${(state.candidate?.artifact?.size ?: 0) / 1024} KiB"
                )
            UpdatePhase.Validating -> IchuText("validating APK…")
            UpdatePhase.Ready -> IchuText("download verified; ready to install")
            UpdatePhase.PermissionRequired ->
                IchuText("allow app installs, then return and tap install again")
            UpdatePhase.SessionsOpen ->
                IchuText("open terminals must be closed before installation")
            else -> Unit
        }
        state.message?.let { IchuText(it, color = colors.textMuted, style = typography.bodySmall) }
        state.candidate?.let { candidate ->
            IchuText(
                "available ${candidate.manifest.versionName} · ${candidate.artifact.size / 1024} KiB",
                style = typography.label,
            )
            IchuText(candidate.notes, style = typography.bodySmall, maxLines = 14)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IchuButton(onClick = vm::check, enabled = !busy, variant = IchuButtonVariant.Outlined) {
                IchuText("check / retry")
            }
            IchuButton(
                onClick = {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(
                                state.candidate?.releaseUrl
                                    ?: "https://github.com/${updates.config.repository}/releases"
                            ),
                        )
                    )
                },
                variant = IchuButtonVariant.Ghost,
            ) {
                IchuText("releases")
            }
        }
        if (state.phase == UpdatePhase.Downloading)
            IchuButton(onClick = updates::cancelDownload) { IchuText("cancel download") }
        if (!busy && state.candidate != null) {
            if (updates.readyFile() == null)
                IchuButton(onClick = updates::download) { IchuText("download APK") }
            else if (state.phase == UpdatePhase.PermissionRequired && Build.VERSION.SDK_INT >= 26)
                IchuButton(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )
                    }
                ) {
                    IchuText("allow app installs")
                }
            else IchuButton(onClick = { updates.install() }) { IchuText("install update") }
            if (state.phase == UpdatePhase.PermissionRequired)
                IchuButton(onClick = { updates.install() }, variant = IchuButtonVariant.Outlined) {
                    IchuText("permission granted · retry install")
                }
            if (state.phase == UpdatePhase.SessionsOpen) {
                IchuText(
                    "Installation replaces this app process. SSH, Mosh and local shells will stop.",
                    style = typography.bodySmall,
                )
                IchuButton(onClick = { updates.install(closeSessions = true) }) {
                    IchuText("close all terminals and install")
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            IchuText("check automatically (24h)", style = typography.label)
            IchuSwitch(
                checked = automatic,
                onCheckedChange = { value ->
                    updates.setAutomatic(value)
                    if (value && Build.VERSION.SDK_INT >= 33)
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
            )
        }
        IchuText(
            "Background checks only notify. Downloads and installation require your action.",
            style = typography.bodySmall,
            color = colors.textMuted,
        )
    }
}
