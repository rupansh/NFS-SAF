package dev.nfssaf

import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.nfssaf.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent {
            val dark=androidx.compose.foundation.isSystemInDarkTheme()
            val colors=if(android.os.Build.VERSION.SDK_INT>=31) {
                if(dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            } else if(dark) darkColorScheme(primary=Color(0xFF8BD5C5)) else lightColorScheme(primary=Color(0xFF246A60),secondary=Color(0xFF4D635D))
            MaterialTheme(colorScheme=colors) { Surface(Modifier.fillMaxSize()) { ConnectionsScreen() } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ConnectionsScreen() {
    val context=LocalContext.current
    val services=remember { Services.get(context) }
    val scope=rememberCoroutineScope()
    var shares by remember { mutableStateOf(services.shares.all()) }
    var editing by remember { mutableStateOf<Share?>(null) }
    var form by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Share?>(null) }
    var closeError by remember { mutableStateOf(services.errors.getString("last",null)) }
    val snackbar=remember { SnackbarHostState() }
    val serviceState by ConnectionService.status.collectAsState()
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted) ConnectionService.start(context)
        else scope.launch { snackbar.showSnackbar("Allow notifications to start connections with a visible status notification") }
    }
    fun startConnections() {
        if(android.os.Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else ConnectionService.start(context)
    }
    if(form) {
        ConnectionForm(editing,onBack={ form=false },onSave={ share ->
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        services.shares.save(share)
                        editing?.let { old -> services.shares.remove(old.id); services.backend.invalidate(old); services.catalog.forget(old.id,RemotePath.Root) }
                    }
                    shares=services.shares.all(); form=false
                    startConnections()
                    snackbar.showSnackbar("${share.name} is available in the Android file picker")
                } catch(e: Exception) { snackbar.showSnackbar(e.message ?: "Could not save connection") }
            }
        })
        return
    }
    Scaffold(topBar={ TopAppBar(title={ Text("NFS SAF",fontWeight=FontWeight.SemiBold) }) },
        snackbarHost={ SnackbarHost(snackbar) },
        floatingActionButton={ ExtendedFloatingActionButton(onClick={ editing=null; form=true }) { Text("+  Add connection") } }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(bottom=100.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Text("Your server.\nIn every file picker.",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.SemiBold)
            Text("Open, edit, and save files on your NFS server from Android apps.",style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("Connected through Android",style=MaterialTheme.typography.titleMedium)
                    Text("After adding a connection, choose its name in an app’s Open or Save screen. Files stream directly from your server.")
                    TextButton(onClick={
                        try { context.startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="*/*"; addCategory(Intent.CATEGORY_OPENABLE) }) }
                        catch(e: Exception) { scope.launch { snackbar.showSnackbar("No Android file picker is available") } }
                    }) { Text("Open file picker") }
                }
            }
            closeError?.let { error ->
                Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("A file could not finish saving",style=MaterialTheme.typography.titleMedium)
                        Text(error)
                        TextButton(onClick={ services.errors.edit().remove("last").apply(); closeError=null }) { Text("Dismiss") }
                    }
                }
            }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(when(serviceState) {
                        OperationGate.State.Running -> "Connections running"
                        OperationGate.State.Draining -> "Finishing operations…"
                        OperationGate.State.Stopped -> "Connections paused"
                    },style=MaterialTheme.typography.titleMedium)
                    Text("A persistent notification keeps you informed while server connections are active.",style=MaterialTheme.typography.bodyMedium)
                    when(serviceState) {
                        OperationGate.State.Stopped -> Button(onClick={ startConnections() },enabled=shares.isNotEmpty()) { Text("Start connections") }
                        OperationGate.State.Running -> TextButton(onClick={ ConnectionService.stop(context) }) { Text("Stop connections") }
                        OperationGate.State.Draining -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            Text("Connections",style=MaterialTheme.typography.titleLarge)
            if(shares.isEmpty()) {
                OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Bring your files closer",style=MaterialTheme.typography.titleMedium)
                    Text("Add a server address and export path to get started.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
            shares.forEach { share ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(share.name,style=MaterialTheme.typography.titleLarge)
                        Text("${share.host}:${share.port}${share.export.value}",color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            SuggestionChip(onClick={},label={ Text(share.protocol.label) })
                            SuggestionChip(onClick={},label={ Text(if(share.readOnly) "Read only" else "Read & write") })
                        }
                        Text("UID ${share.uid} · GID ${share.gid}",style=MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick={ editing=share; form=true }) { Text("Edit") }
                            TextButton(onClick={ removing=share }) { Text("Remove",color=MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
            Text("NFS uses your network or VPN. UID and GID identify the server account; no Android storage permission is needed.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    removing?.let { share -> AlertDialog(onDismissRequest={ removing=null },title={ Text("Remove ${share.name}?") },text={ Text("Your files stay on the server. Apps will need to select this connection again if you add it back.") },confirmButton={ TextButton(onClick={
        scope.launch { withContext(Dispatchers.IO) { services.shares.remove(share.id); services.backend.invalidate(share); services.catalog.forget(share.id,RemotePath.Root) }; shares=services.shares.all(); removing=null }
    }) { Text("Remove") } },dismissButton={ TextButton(onClick={ removing=null }) { Text("Cancel") } }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ConnectionForm(existing: Share?, onBack: () -> Unit, onSave: (Share) -> Unit) {
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var host by rememberSaveable { mutableStateOf(existing?.host ?: "") }
    var path by rememberSaveable { mutableStateOf(existing?.export?.value ?: "/") }
    var uid by rememberSaveable { mutableStateOf(existing?.uid?.toString() ?: "65534") }
    var gid by rememberSaveable { mutableStateOf(existing?.gid?.toString() ?: "65534") }
    var groups by rememberSaveable { mutableStateOf(existing?.groups?.joinToString(",") ?: "") }
    var port by rememberSaveable { mutableStateOf(existing?.port?.toString() ?: "2049") }
    var timeout by rememberSaveable { mutableStateOf(existing?.timeoutSeconds?.toString() ?: "10") }
    var protocol by remember { mutableStateOf(existing?.protocol ?: Protocol.V42) }
    var readOnly by rememberSaveable { mutableStateOf(existing?.readOnly ?: false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf<ConnectionStatus>(ConnectionStatus.Untested) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope=rememberCoroutineScope()
    val busy=status is ConnectionStatus.Connecting
    fun config() = Share(name=name.trim(),host=host.trim().removePrefix("[").removeSuffix("]"),export=RemotePath(path.trim()),protocol=protocol,port=port.toIntOrNull() ?: error("Port must be a number"),uid=uid.toLongOrNull() ?: error("UID must be a number"),gid=gid.toLongOrNull() ?: error("GID must be a number"),groups=if(groups.isBlank()) emptyList() else groups.split(',').map { it.trim().toLongOrNull() ?: error("Groups must be comma-separated numbers") },readOnly=readOnly,timeoutSeconds=timeout.toIntOrNull() ?: error("Timeout must be a number")).validate()
    Scaffold(topBar={ TopAppBar(title={ Text(if(existing==null) "Add connection" else "Edit connection") },navigationIcon={ TextButton(onClick=onBack,enabled=!busy) { Text("Back") } }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("Connect to your NFS server",style=MaterialTheme.typography.headlineSmall)
            Text("Use the export path shown by your server. NFS 4 paths may differ from the server’s local folders.",color=MaterialTheme.colorScheme.onSurfaceVariant)
            @Composable fun field(value: String, set: (String)->Unit, label: String, hint: String?=null, numeric: Boolean=false) {
                OutlinedTextField(value=value,onValueChange={ set(it); status=ConnectionStatus.Untested; error=null },label={ Text(label) },supportingText=hint?.let { { Text(it) } },singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth(),keyboardOptions=KeyboardOptions(keyboardType=if(numeric) KeyboardType.Number else KeyboardType.Text))
            }
            field(name,{name=it},"Connection name","Shown in the Android file picker")
            field(host,{host=it},"Server address","Hostname, IPv4, or IPv6 address")
            field(path,{path=it},"Export path","For example /exports/media")
            Text("Protocol",style=MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Protocol.entries.forEach { p -> FilterChip(selected=protocol==p,onClick={ protocol=p; status=ConnectionStatus.Untested },label={ Text(p.label) },enabled=!busy) } }
            field(uid,{uid=it},"User ID (UID)","Numeric identity sent to the server",true)
            field(gid,{gid=it},"Group ID (GID)",numeric=true)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) { Text("Read only",style=MaterialTheme.typography.titleMedium); Text("Prevent file changes from this connection",style=MaterialTheme.typography.bodySmall) }
                Switch(checked=readOnly,onCheckedChange={ readOnly=it; status=ConnectionStatus.Untested },enabled=!busy)
            }
            TextButton(onClick={ advanced=!advanced }) { Text(if(advanced) "Hide advanced settings" else "Advanced settings") }
            if(advanced) {
                field(groups,{groups=it},"Supplementary groups","Up to 16 numeric GIDs, separated by commas")
                field(port,{port=it},"NFS port",numeric=true)
                field(timeout,{timeout=it},"Request timeout (seconds)","3–30 seconds",true)
                Text("Android uses unprivileged ports. If mounting is denied, enable insecure for this client’s server export and reload the exports. AUTH_SYS is intended for a trusted network or VPN.",style=MaterialTheme.typography.bodySmall)
            }
            if(existing!=null) Text("Saving an edited connection replaces its Android root. Select it again in apps with saved folder access.",style=MaterialTheme.typography.bodySmall)
            when(val current=status) {
                ConnectionStatus.Untested -> Unit
                ConnectionStatus.Connecting -> LinearProgressIndicator(Modifier.fillMaxWidth())
                is ConnectionStatus.Connected -> Text("Connected · ${current.elapsedMillis} ms",color=MaterialTheme.colorScheme.primary)
                is ConnectionStatus.Failed -> Text(current.reason,color=MaterialTheme.colorScheme.error)
            }
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick={
                val share=try { config() } catch(e: Exception) { error=e.message; return@OutlinedButton }
                status=ConnectionStatus.Connecting
                scope.launch {
                    val start=android.os.SystemClock.elapsedRealtime()
                    status=try { withContext(Dispatchers.IO) { NativeSession(share).use { it.stat(RemotePath.Root) } }; ConnectionStatus.Connected(android.os.SystemClock.elapsedRealtime()-start) }
                    catch(e: Exception) { ConnectionStatus.Failed(ConnectionProblem.from(e).message) }
                }
            },enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("Test connection") }
            Button(onClick={ try { onSave(config()) } catch(e: Exception) { error=e.message } },enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("Save & connect") }
        }
    }
}
