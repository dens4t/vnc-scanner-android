package com.dens4t.vncscan

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.text.KeyboardOptions

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                val viewModel: ScanViewModel = viewModel()
                ScannerScreen(viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScannerScreen(viewModel: ScanViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VNC Scanner") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            item { NetworkCard(state, viewModel::detectNetwork) }
            item { SettingsCard(state, viewModel) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.isScanning) {
                        OutlinedButton(onClick = viewModel::cancel, modifier = Modifier.weight(1f)) {
                            Text("Batal")
                        }
                    } else {
                        Button(onClick = viewModel::startScan, modifier = Modifier.weight(1f)) {
                            Text("Mulai Scan")
                        }
                    }
                }
            }

            state.progress?.let { progress ->
                item { ProgressCard(progress, state.isScanning) }
            }

            state.error?.let { message ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            message,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            if (state.results.isNotEmpty()) {
                item {
                    ResultsHeader(
                        state = state,
                        onCopy = {
                            clipboard.setText(AnnotatedString(buildCsv(state.results)))
                            Toast.makeText(context, "CSV disalin", Toast.LENGTH_SHORT).show()
                        },
                        onShare = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "VNC Scanner results")
                                putExtra(Intent.EXTRA_TEXT, buildCsv(state.results))
                            }
                            context.startActivity(Intent.createChooser(send, "Bagikan CSV"))
                        }
                    )
                }
                items(state.results, key = { "${it.ip}:${it.port}" }) { result ->
                    ResultCard(result) {
                        clipboard.setText(AnnotatedString("${result.ip}:${result.port}"))
                        Toast.makeText(context, "${result.ip}:${result.port} disalin", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            if (!state.isScanning && state.progress != null && state.results.isEmpty()) {
                item {
                    Text(
                        "Tidak ada port terbuka pada range tersebut.",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                Text(
                    "Gunakan hanya pada jaringan yang Anda miliki atau berwenang untuk dipindai.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun NetworkCard(state: UiState, onDetect: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Jaringan",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDetect, enabled = !state.isScanning) {
                    Text("Deteksi ulang")
                }
            }
            val network = state.network
            if (network != null) {
                Text(network.cidr, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
                Text(
                    "${network.hostCount} host • ${network.interfaceName ?: "unknown"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    state.networkError ?: "Jaringan tidak terdeteksi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(state: UiState, viewModel: ScanViewModel) {
    var advanced by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Pengaturan scan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = state.rangeInput,
                onValueChange = viewModel::updateRangeInput,
                label = { Text("Range / CIDR") },
                placeholder = { Text("192.168.1.0/24 atau 192.168.1.10-50") },
                singleLine = true,
                enabled = !state.isScanning,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.portsInput,
                    onValueChange = viewModel::updatePortsInput,
                    label = { Text("Port") },
                    singleLine = true,
                    enabled = !state.isScanning,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = state.timeoutInput,
                    onValueChange = viewModel::updateTimeoutInput,
                    label = { Text("Timeout ms") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !state.isScanning,
                    modifier = Modifier.width(130.dp)
                )
            }
            TextButton(onClick = { advanced = !advanced }, enabled = !state.isScanning) {
                Text(if (advanced) "Sembunyikan opsi lanjutan" else "Opsi lanjutan")
            }
            if (advanced) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.concurrencyInput,
                        onValueChange = viewModel::updateConcurrencyInput,
                        label = { Text("Paralel") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        enabled = !state.isScanning,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = state.retriesInput,
                        onValueChange = viewModel::updateRetriesInput,
                        label = { Text("Retry") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        enabled = !state.isScanning,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Resolve hostname", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Reverse DNS untuk hasil yang ditemukan",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.resolveNames,
                        onCheckedChange = { viewModel.toggleResolveNames() },
                        enabled = !state.isScanning
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressCard(progress: ScanProgress, scanning: Boolean) {
    val fraction = if (progress.total > 0) progress.done.toFloat() / progress.total else 0f
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (scanning) "Memindai..." else "Selesai",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text(
                "${progress.done}/${progress.total} koneksi • ${progress.found} terbuka",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ResultsHeader(state: UiState, onCopy: () -> Unit, onShare: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${state.results.size} port terbuka",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onCopy) { Text("Salin CSV") }
            TextButton(onClick = onShare) { Text("Bagikan") }
        }
        val meta = buildString {
            state.scannedRange?.let { append(it) }
            if (state.elapsedMs > 0) {
                if (isNotEmpty()) append(" • ")
                append("${state.elapsedMs} ms")
            }
        }
        if (meta.isNotEmpty()) {
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ResultCard(result: ScanResult, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${result.ip}:${result.port}",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    color = if (result.vncConfirmed) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        if (result.vncConfirmed) "VNC" else "OPEN",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            Text(
                buildString {
                    append("${result.latencyMs} ms")
                    result.hostname?.let { append(" • $it") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            result.banner?.takeIf { it.isNotBlank() }?.let { banner ->
                Text(
                    banner,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun buildCsv(results: List<ScanResult>): String {
    val builder = StringBuilder("IP,Port,VNC,LatencyMs,Hostname,Banner\n")
    for (result in results) {
        val hostname = result.hostname.orEmpty()
        val banner = result.banner.orEmpty().replace("\"", "\"\"")
        builder.append("${result.ip},${result.port},${result.vncConfirmed},${result.latencyMs},$hostname,\"$banner\"\n")
    }
    return builder.toString()
}
