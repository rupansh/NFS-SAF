package dev.nfssaf

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import dev.nfssaf.core.ConnectionProblem
import dev.nfssaf.core.ConnectionStatus
import dev.nfssaf.core.Protocol
import dev.nfssaf.core.ReadAhead
import dev.nfssaf.core.ReadPolicy
import dev.nfssaf.core.RemotePath
import dev.nfssaf.core.Share
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val FALLBACK_DARK_PRIMARY = Color(0xFF8BD5C5)
private val FALLBACK_LIGHT_PRIMARY = Color(0xFF246A60)
private val FALLBACK_LIGHT_SECONDARY = Color(0xFF4D635D)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            val colors =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
                } else if (dark) darkColorScheme(primary = FALLBACK_DARK_PRIMARY)
                else
                    lightColorScheme(
                        primary = FALLBACK_LIGHT_PRIMARY,
                        secondary = FALLBACK_LIGHT_SECONDARY,
                    )
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize()) { ConnectionsScreen() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
// State and event handlers stay together; individual cards live in ConnectionCards.
@Suppress("LongMethod")
private fun ConnectionsScreen(
    ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
) {
    val context = LocalContext.current
    val services = remember { Services.get(context) }
    val scope = rememberCoroutineScope()
    var shares by remember { mutableStateOf(services.shares.all()) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = shares.firstOrNull { it.id.value == editingId }
    var form by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Share?>(null) }
    var closeError by remember { mutableStateOf(services.errors.getString("last", null)) }
    DisposableEffect(services) {
        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
                if (key == "last") closeError = prefs.getString("last", null)
            }
        services.errors.registerOnSharedPreferenceChangeListener(listener)
        onDispose { services.errors.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val snackbar = remember { SnackbarHostState() }
    val serviceState by ConnectionService.status.collectAsState()
    val startConnections = rememberStartConnections {
        scope.launch {
            snackbar.showSnackbar(
                "Allow notifications to start connections with a visible status notification"
            )
        }
    }
    if (form) {
        androidx.activity.compose.BackHandler { form = false }
        ConnectionForm(
            editing,
            onBack = { form = false },
            ioDispatcher = ioDispatcher,
            onSave = { share ->
                withContext(ioDispatcher) {
                    services.shares.replace(editing?.id, share)
                    editing?.let { old ->
                        services.backend.invalidate(old)
                        services.catalog.forget(old.id, RemotePath.Root)
                    }
                }
                shares = services.shares.all()
                form = false
                startConnections()
                scope.launch { snackbar.showSnackbar("Saved ${share.name}") }
            },
        )
        return
    }
    ConnectionsList(
        shares,
        serviceState,
        closeError,
        snackbar,
        ConnectionActions(
            onAdd = {
                editingId = null
                form = true
            },
            onEdit = {
                editingId = it.id.value
                form = true
            },
            onRemove = { removing = it },
            onStart = startConnections,
            onStop = { ConnectionService.stop(context) },
            onOpen = {
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            type = "*/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }
                    )
                } catch (e: android.content.ActivityNotFoundException) {
                    android.util.Log.w("NfsSaf", "No file picker is available", e)
                    scope.launch { snackbar.showSnackbar("No Android file picker is available") }
                }
            },
            onDismissError = {
                services.errors.edit { remove("last") }
                closeError = null
            },
        ),
    )
    removing?.let { share ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${share.name}?") },
            text = {
                Text(
                    "Your files stay on the server. Apps will need to select this connection " +
                        "again if you add it back."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            withContext(ioDispatcher) {
                                services.shares.remove(share.id)
                                services.backend.invalidate(share)
                                services.catalog.forget(share.id, RemotePath.Root)
                            }
                            shares = services.shares.all()
                            removing = null
                        }
                    }
                ) {
                    Text("Remove")
                }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

private enum class EditorOperation {
    Idle,
    Testing,
    Saving,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
// This declarative form keeps saveable field state beside its controls.
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun ConnectionForm(
    existing: Share?,
    onBack: () -> Unit,
    ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    onSave: suspend (Share) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var host by rememberSaveable { mutableStateOf(existing?.host.orEmpty()) }
    var path by rememberSaveable { mutableStateOf(existing?.export?.value ?: "/") }
    var uid by rememberSaveable { mutableStateOf(existing?.uid?.toString() ?: "65534") }
    var gid by rememberSaveable { mutableStateOf(existing?.gid?.toString() ?: "65534") }
    var groups by rememberSaveable { mutableStateOf(existing?.groups?.joinToString(",").orEmpty()) }
    var port by rememberSaveable { mutableStateOf(existing?.port?.toString() ?: "2049") }
    var timeout by rememberSaveable { mutableStateOf(existing?.timeoutSeconds?.toString() ?: "10") }
    var protocol by rememberSaveable { mutableStateOf(existing?.protocol ?: Protocol.V42) }
    var readOnly by rememberSaveable { mutableStateOf(existing?.readOnly ?: false) }
    var readAhead by rememberSaveable {
        mutableStateOf((existing?.readPolicy ?: ReadPolicy.ReadAhead) == ReadPolicy.ReadAhead)
    }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf<ConnectionStatus>(ConnectionStatus.Untested) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var operation by remember { mutableStateOf(EditorOperation.Idle) }
    val busy = operation != EditorOperation.Idle
    fun config() =
        Share(
                name = name.trim(),
                host = host.trim().removePrefix("[").removeSuffix("]"),
                export = RemotePath(path.trim()),
                protocol = protocol,
                port =
                    port.toIntOrNull() ?: throw IllegalArgumentException("Port must be a number"),
                uid = uid.toLongOrNull() ?: throw IllegalArgumentException("UID must be a number"),
                gid = gid.toLongOrNull() ?: throw IllegalArgumentException("GID must be a number"),
                groups =
                    if (groups.isBlank()) emptyList()
                    else
                        groups.split(',').map {
                            it.trim().toLongOrNull()
                                ?: throw IllegalArgumentException(
                                    "Groups must be comma-separated numbers"
                                )
                        },
                readOnly = readOnly,
                timeoutSeconds =
                    timeout.toIntOrNull()
                        ?: throw IllegalArgumentException("Timeout must be a number"),
                readPolicy = if (readAhead) ReadPolicy.ReadAhead else ReadPolicy.Direct,
            )
            .validate()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Add connection" else "Edit connection") },
                navigationIcon = { TextButton(onClick = onBack, enabled = !busy) { Text("Back") } },
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Connect to your NFS server", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Use the export path shown by your server. NFS 4 paths may differ from " +
                    "the server’s local folders.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            @Composable
            fun field(
                value: String,
                set: (String) -> Unit,
                label: String,
                hint: String? = null,
                numeric: Boolean = false,
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        set(it)
                        status = ConnectionStatus.Untested
                        error = null
                    },
                    label = { Text(label) },
                    supportingText = hint?.let { { Text(it) } },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text
                        ),
                )
            }
            field(name, { name = it }, "Connection name", "Shown in the Android file picker")
            field(host, { host = it }, "Server address", "Hostname, IPv4, or IPv6 address")
            field(path, { path = it }, "Export path", "For example /exports/media")
            Text("Protocol", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Protocol.entries.forEach { p ->
                    FilterChip(
                        selected = protocol == p,
                        onClick = {
                            protocol = p
                            status = ConnectionStatus.Untested
                        },
                        label = { Text(p.label) },
                        enabled = !busy,
                    )
                }
            }
            field(uid, { uid = it }, "User ID (UID)", "Numeric identity sent to the server", true)
            field(gid, { gid = it }, "Group ID (GID)", numeric = true)
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Read only", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Prevent file changes from this connection",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = readOnly,
                    onCheckedChange = {
                        readOnly = it
                        status = ConnectionStatus.Untested
                    },
                    enabled = !busy,
                )
            }
            TextButton(onClick = { advanced = !advanced }) {
                Text(if (advanced) "Hide advanced settings" else "Advanced settings")
            }
            if (advanced) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Read ahead", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Faster sequential reads. Turn off when another device " +
                                "edits the same " +
                                "open file; prefetched data is held for up to 250 ms.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = readAhead,
                        onCheckedChange = { readAhead = it },
                        enabled = !busy,
                    )
                }
                field(
                    groups,
                    { groups = it },
                    "Supplementary groups",
                    "Up to 16 numeric GIDs, separated by commas",
                )
                field(port, { port = it }, "NFS port", numeric = true)
                field(timeout, { timeout = it }, "Request timeout (seconds)", "3–30 seconds", true)
                Text(
                    "Android uses unprivileged ports. If mounting is denied, enable insecure " +
                        "for this client’s server export and reload the exports. AUTH_SYS is " +
                        "intended for a trusted network or VPN.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (existing != null)
                Text(
                    "Saving an edited connection replaces its Android root. Select it again " +
                        "in apps with saved folder access.",
                    style = MaterialTheme.typography.bodySmall,
                )
            when (val current = status) {
                ConnectionStatus.Untested -> Unit
                ConnectionStatus.Connecting -> LinearProgressIndicator(Modifier.fillMaxWidth())
                is ConnectionStatus.Connected ->
                    Text(
                        "Connected · ${current.elapsedMillis} ms",
                        color = MaterialTheme.colorScheme.primary,
                    )
                is ConnectionStatus.Failed ->
                    Text(current.reason, color = MaterialTheme.colorScheme.error)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(
                onClick = {
                    val share =
                        try {
                            config()
                        } catch (e: IllegalArgumentException) {
                            error = e.message
                            return@OutlinedButton
                        }
                    operation = EditorOperation.Testing
                    status = ConnectionStatus.Connecting
                    scope.launch {
                        val start = android.os.SystemClock.elapsedRealtime()
                        status =
                            try {
                                withContext(ioDispatcher) {
                                    NativeSession(share).use { it.stat(RemotePath.Root) }
                                }
                                ConnectionStatus.Connected(
                                    android.os.SystemClock.elapsedRealtime() - start
                                )
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                                ConnectionStatus.Failed(ConnectionProblem.from(e).message)
                            } finally {
                                operation = EditorOperation.Idle
                            }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Test connection")
            }
            Button(
                onClick = {
                    val share =
                        try {
                            config()
                        } catch (e: IllegalArgumentException) {
                            error = e.message
                            return@Button
                        }
                    operation = EditorOperation.Saving
                    scope.launch {
                        try {
                            onSave(share)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                            error = e.message ?: "Could not save connection"
                        } finally {
                            operation = EditorOperation.Idle
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (operation == EditorOperation.Saving) "Saving…" else "Save & connect")
            }
        }
    }
}
