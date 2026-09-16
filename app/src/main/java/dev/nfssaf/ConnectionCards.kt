package dev.nfssaf

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nfssaf.core.OperationGate
import dev.nfssaf.core.Share
import kotlinx.coroutines.launch

@Composable
internal fun PickerCard(onOpen: () -> Unit) {
    Card(
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Connected through Android", style = MaterialTheme.typography.titleMedium)
            Text(
                "After adding a connection, choose its name in an app’s Open or Save " +
                    "screen. Files stream directly from your server."
            )
            TextButton(onClick = onOpen) { Text("Open file picker") }
        }
    }
}

@Composable
internal fun SavingErrorCard(error: String, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("A file could not finish saving", style = MaterialTheme.typography.titleMedium)
            Text(error)
            TextButton(onClick = { onDismiss() }) { Text("Dismiss") }
        }
    }
}

@Composable
internal fun ServiceCard(
    serviceState: OperationGate.State,
    canStart: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when (serviceState) {
                    OperationGate.State.Running -> "Connections running"
                    OperationGate.State.Draining -> "Finishing operations…"
                    OperationGate.State.Stopped -> "Connections paused"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "A persistent notification keeps you informed while server connections are active.",
                style = MaterialTheme.typography.bodyMedium,
            )
            when (serviceState) {
                OperationGate.State.Stopped ->
                    Button(onClick = { onStart() }, enabled = canStart) {
                        Text("Start connections")
                    }
                OperationGate.State.Running ->
                    TextButton(onClick = { onStop() }) { Text("Stop connections") }
                OperationGate.State.Draining -> LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
internal fun EmptyShares() {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Bring your files closer", style = MaterialTheme.typography.titleMedium)
            Text(
                "Add a server address and export path to get started.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ShareCard(share: Share, onEdit: (Share) -> Unit, onRemove: (Share) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(share.name, style = MaterialTheme.typography.titleLarge)
            Text(
                "${share.host}:${share.port}${share.export.value}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SuggestionChip(onClick = {}, label = { Text(share.protocol.label) })
                SuggestionChip(
                    onClick = {},
                    label = { Text(if (share.readOnly) "Read only" else "Read & write") },
                )
            }
            Text("UID ${share.uid} · GID ${share.gid}", style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = { onEdit(share) }) { Text("Edit") }
                TextButton(onClick = { onRemove(share) }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

internal data class ConnectionActions(
    val onAdd: () -> Unit,
    val onEdit: (Share) -> Unit,
    val onRemove: (Share) -> Unit,
    val onStart: () -> Unit,
    val onStop: () -> Unit,
    val onOpen: () -> Unit,
    val onDismissError: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectionsList(
    shares: List<Share>,
    serviceState: OperationGate.State,
    closeError: String?,
    snackbar: SnackbarHostState,
    actions: ConnectionActions,
) {
    val onAdd = actions.onAdd
    val onEdit = actions.onEdit
    val onRemove = actions.onRemove
    val onStart = actions.onStart
    val onStop = actions.onStop
    val onOpen = actions.onOpen
    val onDismissError = actions.onDismissError
    Scaffold(
        topBar = { TopAppBar(title = { Text("NFS SAF", fontWeight = FontWeight.SemiBold) }) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { onAdd() }) { Text("+  Add connection") }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                "Your server.\nIn every file picker.",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Open, edit, and save files on your NFS server from Android apps.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PickerCard(onOpen)
            closeError?.let { SavingErrorCard(it, onDismissError) }
            ServiceCard(serviceState, shares.isNotEmpty(), onStart, onStop)
            Text("Connections", style = MaterialTheme.typography.titleLarge)
            if (shares.isEmpty()) EmptyShares()
            shares.forEach { ShareCard(it, onEdit, onRemove) }
            Text(
                "NFS uses your network or VPN. UID and GID identify the server account; " +
                    "no Android storage permission is needed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(80.dp))
        }
    }
}

@Composable
internal fun rememberStartConnections(onDenied: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) ConnectionService.start(context) else onDenied()
        }
    return {
        if (
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        )
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else ConnectionService.start(context)
    }
}
