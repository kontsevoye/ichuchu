package io.github.kontsevoye.ichuchu.ui.screens.AddServer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kontsevoye.ichuchu.model.AuthMethod
import io.github.kontsevoye.ichuchu.model.MultiplexerType
import io.github.kontsevoye.ichuchu.model.Transport
import io.github.kontsevoye.ichuchu.ui.components.IchuButton
import io.github.kontsevoye.ichuchu.ui.components.IchuButtonVariant
import io.github.kontsevoye.ichuchu.ui.components.IchuSegmentedControl
import io.github.kontsevoye.ichuchu.ui.components.IchuSwitch
import io.github.kontsevoye.ichuchu.ui.components.IchuText
import io.github.kontsevoye.ichuchu.ui.components.IchuTextField
import io.github.kontsevoye.ichuchu.ui.theme.IchuColors
import io.github.kontsevoye.ichuchu.ui.theme.IchuTypography

@Composable
fun AddServerScreen(vm: AddServerViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val form by vm.form.collectAsStateWithLifecycle()
    val testState by vm.testState.collectAsStateWithLifecycle()
    val keys by vm.keys.collectAsStateWithLifecycle()
    val colors = IchuColors.current
    val typography = IchuTypography.current

    val scrollState = rememberScrollState()
    val context = LocalContext.current
    var showAdditionalSettings by remember { mutableStateOf(false) }

    LaunchedEffect(vm) {
        vm.errorMessage.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.background)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(scrollState)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IchuText("$ ", style = typography.headline, color = colors.textMuted)
            IchuText(
                if (form.id != null) "edit server" else "add server",
                style = typography.headline,
            )
        }

        SectionHeader("CONNECTION")
        IchuTextField(
            value = form.name,
            onValueChange = vm::updateName,
            label = "Name",
            placeholder = "My server",
            singleLine = true,
            autoFocus = false,
            modifier = Modifier.fillMaxWidth(),
        )
        IchuTextField(
            value = form.host,
            onValueChange = vm::updateHost,
            label = "Host",
            placeholder = "192.168.1.10",
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
            autoFocus = false,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IchuTextField(
                value = form.port,
                onValueChange = vm::updatePort,
                label = "Port",
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                autoFocus = false,
                modifier = Modifier.weight(0.3f),
            )
            IchuTextField(
                value = form.username,
                onValueChange = vm::updateUsername,
                label = "Username",
                placeholder = "root",
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                autoFocus = false,
                modifier = Modifier.weight(0.7f),
            )
        }

        SectionDivider()

        SectionHeader("TRANSPORT")
        IchuSegmentedControl(
            options = listOf(Transport.SSH, Transport.TailscaleSSH, Transport.Mosh),
            labels =
                mapOf(
                    Transport.SSH to "ssh",
                    Transport.TailscaleSSH to "tailscale",
                    Transport.Mosh to "mosh",
                ),
            selected = form.transport,
            onSelect = vm::updateTransport,
        )
        if (form.transport == Transport.TailscaleSSH) {
            IchuText(
                "Connects through the Tailscale network. Use the host's Tailscale IP (100.x.x.x) or MagicDNS name.",
                style = typography.bodySmall,
                color = colors.textMuted,
            )
        }

        SectionDivider()

        SectionHeader("AUTHENTICATION")
        val authOptions =
            when (form.transport) {
                Transport.TailscaleSSH ->
                    listOf(AuthMethod.Password, AuthMethod.Key, AuthMethod.None)
                else -> listOf(AuthMethod.Password, AuthMethod.Key)
            }
        if (form.authMethod == AuthMethod.None && form.transport != Transport.TailscaleSSH) {
            androidx.compose.runtime.SideEffect { vm.updateAuthMethod(AuthMethod.Password) }
        }
        val segmentSelected =
            if (form.authMethod == AuthMethod.KeyWithPassphrase) AuthMethod.Key else form.authMethod
        IchuSegmentedControl(
            options = authOptions,
            labels =
                mapOf(
                    AuthMethod.Password to "password",
                    AuthMethod.Key to "ssh key",
                    AuthMethod.None to "none",
                ),
            selected = segmentSelected,
            onSelect = vm::updateAuthMethod,
        )

        when (form.authMethod) {
            AuthMethod.Password -> {
                IchuTextField(
                    value = form.password,
                    onValueChange = vm::updatePassword,
                    label = "Password",
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    autoFocus = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AuthMethod.Key,
            AuthMethod.KeyWithPassphrase -> {
                KeyAuthSection(
                    form = form,
                    keys = keys,
                    onGenerate = { vm.generateKey(form.name) },
                    onSelectKey = { vm.selectStoredKey(it) },
                    onCopyPublicKey = {
                        if (form.publicKeyOpenSsh.isBlank()) {
                            Toast.makeText(context, "No public key available", Toast.LENGTH_SHORT)
                                .show()
                        } else {
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("SSH Public Key", form.publicKeyOpenSsh)
                            )
                            Toast.makeText(context, "Public key copied", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    IchuSwitch(
                        checked = form.authMethod == AuthMethod.KeyWithPassphrase,
                        onCheckedChange = { checked ->
                            if (checked) {
                                vm.updateAuthMethod(AuthMethod.KeyWithPassphrase)
                            } else {
                                vm.updateAuthMethod(AuthMethod.Key)
                                vm.updateKeyPassphrase("")
                            }
                        },
                    )
                    IchuText("set passphrase", style = typography.label)
                }
                if (form.authMethod == AuthMethod.KeyWithPassphrase) {
                    IchuTextField(
                        value = form.keyPassphrase,
                        onValueChange = vm::updateKeyPassphrase,
                        label = "Passphrase",
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        autoFocus = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            AuthMethod.None -> {
                IchuText(
                    "no SSH auth uses Tailscale SSH policy (`tailscale up --ssh`) on the server. connect through Tailscale SSH instead of regular sshd credentials.",
                    style = typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }

        SectionDivider()

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IchuButton(
                onClick = { showAdditionalSettings = !showAdditionalSettings },
                variant = IchuButtonVariant.Outlined,
                bracketed = true,
            ) {
                IchuText("additional settings", style = typography.label)
            }
        }
        if (showAdditionalSettings) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                IchuText("require biometric auth", style = typography.label)
                IchuSwitch(
                    checked = form.requireAuthOnConnect,
                    onCheckedChange = vm::updateRequireAuthOnConnect,
                )
            }
            SectionDivider()
            MultiplexerSection(selected = form.multiplexer, onSelect = vm::updateMultiplexer)
            SectionDivider()
            PostConnectActionSection(
                command = form.postConnectCommand,
                onCommandChange = vm::updatePostConnectCommand,
            )
            SectionDivider()
        }

        val canTest = form.host.isNotBlank() && form.username.isNotBlank()
        IchuButton(
            onClick = vm::testConnection,
            enabled = canTest,
            variant = IchuButtonVariant.Outlined,
            bracketed = true,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val label =
                when (testState.status) {
                    ConnectionTestStatus.Idle -> "test connection"
                    ConnectionTestStatus.Running -> "testing…"
                    ConnectionTestStatus.Success -> "connected"
                    ConnectionTestStatus.Error -> "test failed"
                }
            IchuText(label, style = typography.label)
        }
        if (testState.status == ConnectionTestStatus.Error && testState.message != null) {
            IchuText(testState.message ?: "", style = typography.bodySmall, color = colors.error)
        }
        IchuButton(
            onClick = { vm.save(onBack) },
            enabled = form.canSave(),
            variant = IchuButtonVariant.Outlined,
            bracketed = true,
            borderColor = colors.accent,
            modifier = Modifier.fillMaxWidth(),
        ) {
            IchuText("save", style = typography.label, color = colors.accent)
        }
    }
}

@Composable
private fun SectionDivider() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(IchuColors.current.border))
}

@Composable
private fun SectionHeader(label: String) {
    val colors = IchuColors.current
    val typography = IchuTypography.current
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        IchuText("── ", style = typography.labelSmall, color = colors.textMuted)
        IchuText(label, style = typography.labelSmall, color = colors.textMuted)
        IchuText(" ", style = typography.labelSmall, color = colors.textMuted)
        Box(modifier = Modifier.height(1.dp).background(colors.textMuted).fillMaxWidth())
    }
}

@Composable
private fun KeyAuthSection(
    form: AddServerForm,
    keys: List<io.github.kontsevoye.ichuchu.model.SshKey>,
    onGenerate: () -> Unit,
    onSelectKey: (Long?) -> Unit,
    onCopyPublicKey: () -> Unit,
) {
    val typography = IchuTypography.current
    val colors = IchuColors.current
    val selectedKey = keys.firstOrNull { it.id == form.keyId }
    var pickerExpanded by remember { mutableStateOf(false) }

    if (selectedKey != null) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IchuText("● ", style = typography.body, color = colors.accent)
            IchuText(selectedKey.name, style = typography.body, color = colors.textPrimary)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IchuButton(
                onClick = onCopyPublicKey,
                variant = IchuButtonVariant.Outlined,
                bracketed = true,
                borderColor = colors.accent,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier.weight(1f),
            ) {
                IchuText("copy public key", style = typography.labelSmall, color = colors.accent)
            }
            IchuButton(
                onClick = onGenerate,
                variant = IchuButtonVariant.Outlined,
                bracketed = true,
                modifier = Modifier.weight(1f),
            ) {
                IchuText("new key", style = typography.label)
            }
        }
    } else {
        IchuText(
            "generate an Ed25519 key, then copy the public key to ~/.ssh/authorized_keys on the remote host.",
            style = typography.bodySmall,
            color = colors.textMuted,
        )
        IchuButton(
            onClick = onGenerate,
            variant = IchuButtonVariant.Outlined,
            bracketed = true,
            modifier = Modifier.fillMaxWidth(),
        ) {
            IchuText("generate key", style = typography.label)
        }
    }

    if (keys.isNotEmpty()) {
        IchuButton(
            onClick = { pickerExpanded = !pickerExpanded },
            variant = IchuButtonVariant.Ghost,
            bracketed = true,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        ) {
            IchuText(
                text = if (selectedKey != null) "change key" else "use existing key",
                style = typography.labelSmall,
                color = colors.accent,
            )
        }
        if (pickerExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                keys.forEach { key ->
                    val isSelected = key.id == form.keyId
                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .clickable {
                                    onSelectKey(key.id)
                                    pickerExpanded = false
                                }
                                .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        IchuText(
                            text = (if (isSelected) "● " else "  ") + key.name,
                            style = typography.body,
                            color = if (isSelected) colors.accent else colors.textPrimary,
                        )
                        IchuText(
                            key.algorithm,
                            style = typography.bodySmall,
                            color = colors.textMuted,
                        )
                    }
                }
            }
        }
    }
}

// The segmented control requires a non-null selection, but "no persistence" is
// modeled as a null MultiplexerType, so wrap the choices in a UI-only enum.
private enum class MultiplexerOption(val type: MultiplexerType?) {
    Off(null),
    Tmux(MultiplexerType.Tmux),
    Zellij(MultiplexerType.Zellij),
    Zmx(MultiplexerType.Zmx),
}

@Composable
private fun MultiplexerSection(selected: MultiplexerType?, onSelect: (MultiplexerType?) -> Unit) {
    val colors = IchuColors.current
    val typography = IchuTypography.current
    SectionHeader("SESSION PERSISTENCE")
    val options = MultiplexerOption.entries.toList()
    val selectedOption = options.firstOrNull { it.type == selected } ?: MultiplexerOption.Off
    IchuSegmentedControl(
        options = options,
        labels =
            mapOf(
                MultiplexerOption.Off to "off",
                MultiplexerOption.Tmux to "tmux",
                MultiplexerOption.Zellij to "zellij",
                MultiplexerOption.Zmx to "zmx",
            ),
        selected = selectedOption,
        onSelect = { onSelect(it.type) },
        disabledOptions = setOf(MultiplexerOption.Zellij),
    )
}

@Composable
private fun PostConnectActionSection(command: String, onCommandChange: (String) -> Unit) {
    val colors = IchuColors.current
    val typography = IchuTypography.current
    IchuText("auto-run on connect", style = typography.label)
    IchuTextField(
        value = command,
        onValueChange = onCommandChange,
        label = "",
        placeholder = "e.g. tmux attach -t main",
        showLabel = false,
        singleLine = true,
        autoFocus = false,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        IchuButton(
            onClick = { onCommandChange("") },
            enabled = command.isNotBlank(),
            variant = IchuButtonVariant.Ghost,
            bracketed = true,
            borderColor = colors.textMuted,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        ) {
            IchuText("clear", style = typography.labelSmall, color = colors.textMuted)
        }
    }
}
