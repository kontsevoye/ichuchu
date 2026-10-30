package io.github.kontsevoye.ichuchu.ui.screens.ServerList

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kontsevoye.ichuchu.model.HostProfile
import io.github.kontsevoye.ichuchu.model.Transport
import io.github.kontsevoye.ichuchu.service.terminal.TerminalSessionRepository
import io.github.kontsevoye.ichuchu.ui.components.IchuButton
import io.github.kontsevoye.ichuchu.ui.components.IchuButtonVariant
import io.github.kontsevoye.ichuchu.ui.components.IchuCard
import io.github.kontsevoye.ichuchu.ui.components.IchuDialog
import io.github.kontsevoye.ichuchu.ui.components.IchuText
import io.github.kontsevoye.ichuchu.ui.components.IchuTextField
import io.github.kontsevoye.ichuchu.ui.components.TuiBadge
import io.github.kontsevoye.ichuchu.ui.components.rememberHostListReorderState
import io.github.kontsevoye.ichuchu.ui.theme.IchuColors
import io.github.kontsevoye.ichuchu.ui.theme.IchuTypography
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun ServerListScreen(
    hosts: List<HostProfile>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onAddServer: () -> Unit,
    localShellEnabled: Boolean,
    onOpenLocalShell: () -> Unit,
    onEditServer: (Long) -> Unit,
    onConnectServer: (Long) -> Unit,
    onDeleteServer: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    allHosts: List<HostProfile> = hosts,
    onReorderHosts: suspend (List<Long>) -> Boolean = { false },
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val application = context.applicationContext as Application
    val sessionRepo = remember(application) { TerminalSessionRepository.getInstance(application) }
    val connectedHostIds by sessionRepo.connectedHostIds.collectAsStateWithLifecycle()
    val openTabs by sessionRepo.tabs.collectAsStateWithLifecycle()
    val hasOpenLocalShell = openTabs.any { it.spec.transport == Transport.LocalShell }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val edge = with(LocalDensity.current) { 64.dp.toPx() }
    val reorder =
        rememberHostListReorderState(hosts, listState, edge, if (localShellEnabled) 1 else 0) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    val canReorder = searchQuery.isBlank() && hosts.size > 1 && !reorder.saving

    var selectedHostId by remember { mutableStateOf<Long?>(null) }
    var pendingDisconnectHostId by remember { mutableStateOf<Long?>(null) }
    var pendingConnectHostId by remember { mutableStateOf<Long?>(null) }
    var pendingLocalShell by remember { mutableStateOf(false) }
    var isSearchVisible by remember { mutableStateOf(false) }
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            val hostId = pendingConnectHostId
            val openLocalShell = pendingLocalShell
            pendingConnectHostId = null
            pendingLocalShell = false
            when {
                openLocalShell -> onOpenLocalShell()
                hostId != null -> onConnectServer(hostId)
            }
        }
    LaunchedEffect(hosts, selectedHostId) {
        val selected_id = selectedHostId
        if (selected_id != null && hosts.none { it.id == selected_id }) {
            selectedHostId = null
        }
    }

    fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED

    fun openLocalShellWithPermission() {
        if (needsNotificationPermission()) {
            pendingLocalShell = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onOpenLocalShell()
        }
    }

    val colors = IchuColors.current
    val typography = IchuTypography.current
    Box(modifier = modifier.fillMaxSize().background(colors.background)) {
        Column(
            modifier =
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IchuText("$ ", style = typography.headline, color = colors.textMuted)
                    IchuText("ichuchu", style = typography.headline)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isSearchVisible && hosts.isNotEmpty()) {
                        IchuButton(
                            onClick = { isSearchVisible = true },
                            variant = IchuButtonVariant.Outlined,
                            bracketed = true,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            IchuText(
                                "filter",
                                style = typography.label,
                                color = colors.textSecondary,
                            )
                        }
                    }
                    IchuButton(
                        onClick = onOpenSettings,
                        variant = IchuButtonVariant.Outlined,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        IchuText("settings", style = typography.label, color = colors.textSecondary)
                    }
                }
            }

            if (isSearchVisible) {
                IchuTextField(
                    value = searchQuery,
                    onValueChange = { value ->
                        onSearchChange(value)
                        if (value.isBlank()) isSearchVisible = false
                    },
                    label = "",
                    placeholder = "filter hosts",
                    singleLine = true,
                    showLabel = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionHeader("HOSTS")

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).testTag("host_list"),
                contentPadding = PaddingValues(bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                userScrollEnabled = reorder.draggingId == null,
            ) {
                if (localShellEnabled) {
                    item(key = "local-shell") {
                        LocalShellCard(
                            isOpen = hasOpenLocalShell,
                            onOpen = { openLocalShellWithPermission() },
                        )
                    }
                }
                if (hosts.isEmpty()) {
                    item(key = "empty") {
                        if (searchQuery.isBlank()) {
                            EmptyState()
                        } else {
                            IchuText(
                                "no hosts match",
                                style = typography.body,
                                color = colors.textMuted,
                            )
                        }
                    }
                } else {
                    items(reorder.hosts, key = { it.id }) { host ->
                        val isConnected = host.id in connectedHostIds
                        HostCard(
                            host = host,
                            modifier = reorder.itemModifier(host.id),
                            reorderHandle = {
                                if (canReorder) {
                                    Box(
                                        Modifier.size(48.dp)
                                            .testTag("host_reorder_${host.id}")
                                            .semantics {
                                                contentDescription =
                                                    "Reorder ${host.name}; hold and drag"
                                                customActions = buildList {
                                                    val index =
                                                        reorder.hosts.indexOfFirst {
                                                            it.id == host.id
                                                        }
                                                    if (index > 0) {
                                                        add(
                                                            CustomAccessibilityAction("Move up") {
                                                                scope.launch {
                                                                    reorder.move(
                                                                        host.id,
                                                                        -1,
                                                                        onReorderHosts,
                                                                    )
                                                                }
                                                                true
                                                            }
                                                        )
                                                    }
                                                    if (index < reorder.hosts.lastIndex) {
                                                        add(
                                                            CustomAccessibilityAction("Move down") {
                                                                scope.launch {
                                                                    reorder.move(
                                                                        host.id,
                                                                        1,
                                                                        onReorderHosts,
                                                                    )
                                                                }
                                                                true
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                            .pointerInput(reorder, host.id) {
                                                try {
                                                    awaitEachGesture {
                                                        // Own the handle's press so the card's
                                                        // delete
                                                        // gesture cannot claim it.
                                                        val down =
                                                            awaitFirstDown(
                                                                requireUnconsumed = false
                                                            )
                                                        down.consume()
                                                        val longPress =
                                                            awaitLongPressOrCancellation(down.id)
                                                        if (longPress != null) {
                                                            selectedHostId = null
                                                            reorder.start(host.id)
                                                            if (reorder.draggingId == host.id) {
                                                                haptics.performHapticFeedback(
                                                                    HapticFeedbackType.LongPress
                                                                )
                                                            }
                                                            val dropped =
                                                                drag(longPress.id) { change ->
                                                                    reorder.drag(
                                                                        change.positionChange().y
                                                                    )
                                                                    change.consume()
                                                                }
                                                            if (dropped) {
                                                                scope.launch {
                                                                    reorder.drop(onReorderHosts)
                                                                }
                                                            } else {
                                                                reorder.cancel()
                                                            }
                                                        }
                                                    }
                                                } finally {
                                                    if (reorder.draggingId == host.id) {
                                                        reorder.cancel()
                                                    }
                                                }
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        IchuText(
                                            "≡",
                                            style = typography.title,
                                            color = colors.textMuted,
                                        )
                                    }
                                }
                            },
                            routeLabel =
                                host.jumpHostIds.joinToString(" → ") { id ->
                                    allHosts.firstOrNull { it.id == id }?.name ?: "#$id"
                                },
                            isConnected = isConnected,
                            isSelected = selectedHostId == host.id,
                            onLongPress = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                selectedHostId = host.id
                            },
                            onCancelSelection = { selectedHostId = null },
                            onDeleteSelection = {
                                val is_selected_connected = host.id in connectedHostIds
                                if (is_selected_connected) {
                                    Toast.makeText(
                                            context,
                                            "Disconnect before deleting this host",
                                            Toast.LENGTH_SHORT,
                                        )
                                        .show()
                                } else {
                                    onDeleteServer(host.id)
                                    selectedHostId = null
                                }
                            },
                            onEdit = {
                                selectedHostId = null
                                onEditServer(host.id)
                            },
                            onConnect = {
                                selectedHostId = null
                                if (needsNotificationPermission()) {
                                    pendingConnectHostId = host.id
                                    notificationPermissionLauncher.launch(
                                        Manifest.permission.POST_NOTIFICATIONS
                                    )
                                } else {
                                    onConnectServer(host.id)
                                }
                            },
                            onDisconnect = { pendingDisconnectHostId = host.id },
                        )
                    }
                }
            }
        }

        IchuButton(
            onClick = onAddServer,
            variant = IchuButtonVariant.Filled,
            bracketed = true,
            modifier =
                Modifier.align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp)
                    .height(44.dp),
        ) {
            IchuText("+ add server", style = typography.label, color = colors.onAccent)
        }

        val pendingDisconnectHost = hosts.firstOrNull { it.id == pendingDisconnectHostId }
        if (pendingDisconnectHost != null) {
            val sessionCount = openTabs.count { it.spec.hostId == pendingDisconnectHost.id }
            IchuDialog(
                title = "Disconnect ${pendingDisconnectHost.name}?",
                confirmLabel = "Disconnect",
                dismissLabel = "Cancel",
                onConfirm = {
                    selectedHostId = null
                    openTabs
                        .asSequence()
                        .filter { it.spec.hostId == pendingDisconnectHost.id }
                        .map { it.id }
                        .toList()
                        .forEach(sessionRepo::closeTab)
                    pendingDisconnectHostId = null
                },
                onDismiss = { pendingDisconnectHostId = null },
            ) {
                val sessionLabel = if (sessionCount == 1) "session" else "sessions"
                IchuText(
                    "This closes $sessionCount $sessionLabel on this host.",
                    style = typography.body,
                    color = colors.warning,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    val colors = IchuColors.current
    val typography = IchuTypography.current
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        IchuText(
            "── $label",
            style = typography.labelSmall,
            color = colors.textSecondary,
            modifier = Modifier.background(colors.background),
        )
    }
}

@Composable
private fun EmptyState() {
    val colors = IchuColors.current
    val typography = IchuTypography.current
    IchuCard(background = colors.surfaceVariant) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            IchuText("# no hosts configured", style = typography.body, color = colors.textMuted)
            Spacer(modifier = Modifier.height(8.dp))
            IchuText(
                "add your first host to connect quickly.",
                style = typography.bodySmall,
                color = colors.textSecondary,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LocalShellCard(isOpen: Boolean, onOpen: () -> Unit) {
    val colors = IchuColors.current
    val typography = IchuTypography.current

    IchuCard(
        modifier = Modifier.fillMaxWidth().height(114.dp).combinedClickable(onClick = onOpen),
        background = colors.surfaceVariant,
        border = if (isOpen) colors.success else colors.border,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IchuText(
                        if (isOpen) "● " else "○ ",
                        style = typography.title,
                        color = if (isOpen) colors.success else colors.textMuted,
                    )
                    IchuText("local shell", style = typography.title)
                }
                TuiBadge(text = "android", color = colors.accentSecondary)
            }
            Spacer(modifier = Modifier.height(2.dp))
            IchuText(
                "> this device · app sandbox",
                style = typography.body,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IchuButton(
                    onClick = onOpen,
                    variant = IchuButtonVariant.Outlined,
                    bracketed = true,
                    borderColor = colors.accentSecondary,
                    contentDescription = "Open local shell",
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    IchuText("open", style = typography.label, color = colors.accentSecondary)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun HostCard(
    routeLabel: String,
    host: HostProfile,
    isConnected: Boolean,
    isSelected: Boolean,
    onLongPress: () -> Unit,
    onCancelSelection: () -> Unit,
    onDeleteSelection: () -> Unit,
    onEdit: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
    reorderHandle: @Composable () -> Unit = {},
) {
    val colors = IchuColors.current
    val typography = IchuTypography.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val maxSwipePx = with(density) { 132.dp.toPx() }
    val disconnectThresholdPx = with(density) { 72.dp.toPx() }
    val swipeOffsetX = remember(host.id) { Animatable(0f) }
    val hostLabel = "> ${host.username}@${host.host}:${host.port}"

    LaunchedEffect(isConnected) {
        if (!isConnected) {
            swipeOffsetX.snapTo(0f)
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (isSelected) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IchuButton(
                        onClick = onCancelSelection,
                        variant = IchuButtonVariant.Ghost,
                        bracketed = true,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        IchuText("cancel", style = typography.label, color = colors.textSecondary)
                    }
                    IchuButton(
                        onClick = onDeleteSelection,
                        variant = IchuButtonVariant.Outlined,
                        bracketed = true,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        IchuText("delete", style = typography.label, color = colors.error)
                    }
                }
            }
        }

        Box(modifier = Modifier.fillMaxWidth().testTag("host_card_${host.id}")) {
            if (isConnected) {
                Box(
                    modifier =
                        Modifier.matchParentSize()
                            .background(colors.warning.copy(alpha = 0.82f))
                            .padding(end = 14.dp)
                ) {
                    IchuText(
                        "[disconnect]",
                        style = typography.label,
                        color = colors.background,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }

            Box(
                modifier =
                    Modifier.offset { IntOffset(swipeOffsetX.value.roundToInt(), 0) }
                        .fillMaxWidth()
                        .pointerInput(host.id, isConnected) {
                            if (!isConnected) {
                                return@pointerInput
                            }
                            detectHorizontalDragGestures(
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    val next =
                                        (swipeOffsetX.value + dragAmount).coerceIn(-maxSwipePx, 0f)
                                    scope.launch { swipeOffsetX.snapTo(next) }
                                },
                                onDragEnd = {
                                    if (swipeOffsetX.value <= -disconnectThresholdPx) {
                                        onDisconnect()
                                        scope.launch {
                                            swipeOffsetX.animateTo(0f, animationSpec = tween(140))
                                        }
                                    } else {
                                        scope.launch {
                                            swipeOffsetX.animateTo(0f, animationSpec = tween(140))
                                        }
                                    }
                                },
                            )
                        }
            ) {
                IchuCard(
                    modifier =
                        Modifier.fillMaxWidth()
                            .combinedClickable(onClick = onConnect, onLongClick = onLongPress),
                    background = colors.surfaceVariant,
                    border =
                        when {
                            isSelected -> colors.warning
                            isConnected -> colors.success
                            else -> colors.border
                        },
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IchuText(
                                    if (isConnected) "● " else "○ ",
                                    style = typography.title,
                                    color = if (isConnected) colors.success else colors.textMuted,
                                )
                                IchuText(
                                    host.name,
                                    style = typography.title,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            reorderHandle()
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IchuText(
                                hostLabel,
                                style = typography.body,
                                color = colors.textSecondary,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (host.transport == Transport.TailscaleSSH) {
                                TuiBadge(text = "tailscale", color = colors.accent)
                            }
                        }
                        if (host.jumpHostIds.isNotEmpty()) {
                            IchuText(
                                "via $routeLabel",
                                style = typography.bodySmall,
                                color = colors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            IchuButton(
                                onClick = onEdit,
                                variant = IchuButtonVariant.Ghost,
                                bracketed = true,
                                borderColor = colors.textMuted,
                                testTag = "host_edit_${host.id}",
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                IchuText("edit", style = typography.label, color = colors.textMuted)
                            }
                            IchuButton(
                                onClick = { if (isConnected) onDisconnect() else onConnect() },
                                variant = IchuButtonVariant.Outlined,
                                bracketed = true,
                                borderColor =
                                    if (isConnected) colors.warning else colors.accentSecondary,
                                testTag = "host_connect_${host.id}",
                                contentDescription =
                                    if (isConnected) {
                                        "Disconnect from ${host.name}"
                                    } else {
                                        "Connect to ${host.name}"
                                    },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                IchuText(
                                    if (isConnected) "disconnect" else "connect",
                                    style = typography.label,
                                    color =
                                        if (isConnected) colors.warning else colors.accentSecondary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
