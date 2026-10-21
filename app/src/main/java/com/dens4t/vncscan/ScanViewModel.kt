package com.dens4t.vncscan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val network: LocalNetwork? = null,
    val networkError: String? = null,
    val rangeInput: String = "",
    val portsInput: String = "5900-5905",
    val timeoutInput: String = "500",
    val concurrencyInput: String = "128",
    val retriesInput: String = "0",
    val resolveNames: Boolean = true,
    val isScanning: Boolean = false,
    val progress: ScanProgress? = null,
    val results: List<ScanResult> = emptyList(),
    val error: String? = null,
    val elapsedMs: Long = 0,
    val scannedRange: String? = null
)

class ScanViewModel(application: Application) : AndroidViewModel(application) {
    private val scanner = VncScanner()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var scanJob: Job? = null

    init {
        detectNetwork()
    }

    fun detectNetwork() {
        val network = runCatching { NetworkDetector.detect(getApplication()) }.getOrNull()
        _state.update { current ->
            current.copy(
                network = network,
                networkError = if (network == null) {
                    "Jaringan tidak terdeteksi. Isi range secara manual."
                } else null,
                rangeInput = network?.let { "${it.startIp}-${it.endIp}" } ?: current.rangeInput
            )
        }
    }

    fun updateRangeInput(value: String) = _state.update { it.copy(rangeInput = value, error = null) }
    fun updatePortsInput(value: String) = _state.update { it.copy(portsInput = value, error = null) }
    fun updateTimeoutInput(value: String) = _state.update { it.copy(timeoutInput = value, error = null) }
    fun updateConcurrencyInput(value: String) = _state.update { it.copy(concurrencyInput = value, error = null) }
    fun updateRetriesInput(value: String) = _state.update { it.copy(retriesInput = value, error = null) }
    fun toggleResolveNames() = _state.update { it.copy(resolveNames = !it.resolveNames) }

    fun cancel() {
        scanJob?.cancel()
        scanJob = null
        _state.update { it.copy(isScanning = false, progress = null) }
    }

    fun startScan() {
        if (_state.value.isScanning) return
        val snapshot = _state.value

        val ports = IpMath.parsePorts(snapshot.portsInput)
        if (ports == null) {
            _state.update { it.copy(error = "Format port tidak valid. Contoh: 5900-5905 atau 5900,5901") }
            return
        }

        val timeout = snapshot.timeoutInput.toIntOrNull()
        if (timeout == null || timeout !in 100..5000) {
            _state.update { it.copy(error = "Timeout harus 100-5000 ms.") }
            return
        }

        val concurrency = snapshot.concurrencyInput.toIntOrNull()
        if (concurrency == null || concurrency !in 1..512) {
            _state.update { it.copy(error = "Concurrency harus 1-512.") }
            return
        }

        val retries = snapshot.retriesInput.toIntOrNull()
        if (retries == null || retries !in 0..3) {
            _state.update { it.copy(error = "Retry harus 0-3.") }
            return
        }

        val range = IpMath.parseRangeOrCidr(snapshot.rangeInput) ?: snapshot.network?.let { it.start to it.end }
        if (range == null) {
            _state.update { it.copy(error = "Range tidak valid. Contoh: 192.168.1.0/24 atau 192.168.1.10-50") }
            return
        }

        val hostCount = range.second - range.first + 1
        if (hostCount <= 0) {
            _state.update { it.copy(error = "Range kosong.") }
            return
        }
        if (hostCount > 4096) {
            _state.update { it.copy(error = "Range terlalu besar ($hostCount host). Maksimal 4096 host.") }
            return
        }

        val targetCount = hostCount * ports.size
        if (targetCount > 24576) {
            _state.update { it.copy(error = "Terlalu banyak target ($targetCount). Kurangi host atau port.") }
            return
        }

        val targets = ArrayList<ScanTarget>(targetCount.toInt())
        var value = range.first
        while (value <= range.second) {
            val ip = IpMath.longToIp(value)
            for (port in ports) targets.add(ScanTarget(ip, port))
            value++
        }

        val rangeLabel = "${IpMath.longToIp(range.first)} - ${IpMath.longToIp(range.second)}"

        scanJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            _state.update {
                it.copy(
                    isScanning = true,
                    progress = ScanProgress(0, targets.size, 0),
                    results = emptyList(),
                    error = null,
                    elapsedMs = 0,
                    scannedRange = rangeLabel
                )
            }
            try {
                scanner.scan(
                    targets = targets,
                    connectTimeoutMs = timeout,
                    concurrency = concurrency,
                    retries = retries,
                    resolveNames = snapshot.resolveNames,
                    onFound = { result ->
                        _state.update { current ->
                            val merged = (current.results + result).sortedWith(
                                compareBy(
                                    { runCatching { IpMath.ipToLong(it.ip) }.getOrDefault(0L) },
                                    { it.port }
                                )
                            )
                            current.copy(results = merged)
                        }
                    },
                    onProgress = { progress ->
                        _state.update { it.copy(progress = progress) }
                    }
                )
                _state.update {
                    it.copy(
                        isScanning = false,
                        elapsedMs = System.currentTimeMillis() - startedAt
                    )
                }
            } catch (cancelled: CancellationException) {
                _state.update {
                    it.copy(
                        isScanning = false,
                        progress = null,
                        elapsedMs = System.currentTimeMillis() - startedAt
                    )
                }
                throw cancelled
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        isScanning = false,
                        progress = null,
                        error = error.message ?: "Scan gagal.",
                        elapsedMs = System.currentTimeMillis() - startedAt
                    )
                }
            }
        }
    }
}
