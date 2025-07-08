package com.example.solanakotlincomposescaffold.managers

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class BluetoothState(
    val isEnabled: Boolean = false,
    val isScanning: Boolean = false,
    val devices: List<BluetoothDevice> = emptyList(),
    val errorMessage: String? = null,
    val permissionsGranted: Boolean = false,
    val locationEnabled: Boolean = false,
    val adapterState: String = "Unknown",
    val discoveryAttempts: Int = 0,
    val lastDiscoveryMethod: String = "None"
)

@Singleton
class BluetoothManagerHelper @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "BluetoothManagerHelper"
        private const val DISCOVERY_TIMEOUT_MS = 12000L
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 1000L
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val handler = Handler(Looper.getMainLooper())

    private val _bluetoothState = MutableStateFlow(BluetoothState())
    val bluetoothState: StateFlow<BluetoothState> = _bluetoothState

    private val discoveredDevices = mutableSetOf<BluetoothDevice>()
    private var isReceiverRegistered = false
    private var discoveryTimeoutRunnable: Runnable? = null
    private var retryCount = 0

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Broadcast received: ${intent?.action}")

            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }

                    device?.let {
                        Log.d(TAG, "Device found: ${getDeviceName(it)} - ${it.address}")
                        discoveredDevices.add(it)
                        updateDevicesList()

                        // Reset error message when we start finding devices
                        _bluetoothState.value = _bluetoothState.value.copy(errorMessage = null)
                    }
                }

                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    Log.d(TAG, "Discovery started successfully")
                    retryCount = 0 // Reset retry count on successful start
                    cancelDiscoveryTimeout()
                    _bluetoothState.value = _bluetoothState.value.copy(
                        isScanning = true,
                        errorMessage = null,
                        lastDiscoveryMethod = "Standard"
                    )

                    // Set a timeout for discovery
                    discoveryTimeoutRunnable = Runnable {
                        Log.d(TAG, "Discovery timeout reached")
                        if (bluetoothAdapter?.isDiscovering == true) {
                            stopDiscovery()
                        }
                    }
                    handler.postDelayed(discoveryTimeoutRunnable!!, DISCOVERY_TIMEOUT_MS)
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Log.d(TAG, "Discovery finished. Found ${discoveredDevices.size} devices")
                    cancelDiscoveryTimeout()
                    _bluetoothState.value = _bluetoothState.value.copy(
                        isScanning = false,
                        discoveryAttempts = _bluetoothState.value.discoveryAttempts + 1
                    )
                }

                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    val isEnabled = state == BluetoothAdapter.STATE_ON
                    val stateString = getBluetoothStateString(state)
                    Log.d(TAG, "Bluetooth state changed: $stateString (enabled=$isEnabled)")

                    _bluetoothState.value = _bluetoothState.value.copy(
                        isEnabled = isEnabled,
                        adapterState = stateString
                    )

                    if (isEnabled) {
                        updateDevicesList()
                    }
                }

                BluetoothAdapter.ACTION_SCAN_MODE_CHANGED -> {
                    val scanMode = intent.getIntExtra(BluetoothAdapter.EXTRA_SCAN_MODE, BluetoothAdapter.ERROR)
                    Log.d(TAG, "Scan mode changed: ${getScanModeString(scanMode)}")
                }
            }
        }
    }

    fun isBluetoothSupported(): Boolean = bluetoothAdapter != null

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    fun isLocationEnabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            true
        } else {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    fun hasRequiredPermissions(): Boolean {
        val hasPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

        return hasPermissions
    }

    fun getRequiredPermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }

    fun startDiscovery() {
        Log.d(TAG, "Starting discovery (attempt ${retryCount + 1})...")

        if (!preflightChecks()) {
            return
        }

        // Clear previous results
        discoveredDevices.clear()

        // Cancel any ongoing discovery first
        if (bluetoothAdapter?.isDiscovering == true) {
            Log.d(TAG, "Canceling ongoing discovery")
            bluetoothAdapter.cancelDiscovery()

            handler.postDelayed({
                performDiscoveryAttempt()
            }, 200) // Longer delay for cancellation
        } else {
            performDiscoveryAttempt()
        }
    }

    private fun preflightChecks(): Boolean {
        if (!isBluetoothSupported()) {
            val error = "Bluetooth not supported on this device"
            Log.e(TAG, error)
            _bluetoothState.value = _bluetoothState.value.copy(errorMessage = error)
            return false
        }

        if (!hasRequiredPermissions()) {
            val error = "Bluetooth permissions not granted"
            Log.e(TAG, error)
            _bluetoothState.value = _bluetoothState.value.copy(errorMessage = error)
            return false
        }

        if (!isBluetoothEnabled()) {
            val error = "Bluetooth is not enabled"
            Log.e(TAG, error)
            _bluetoothState.value = _bluetoothState.value.copy(errorMessage = error)
            return false
        }

        if (!isLocationEnabled()) {
            val error = "Location services must be enabled for Bluetooth scanning"
            Log.e(TAG, error)
            _bluetoothState.value = _bluetoothState.value.copy(errorMessage = error)
            return false
        }

        val adapterState = bluetoothAdapter?.state
        if (adapterState != BluetoothAdapter.STATE_ON) {
            val error = "Bluetooth adapter not in ON state: ${getBluetoothStateString(adapterState ?: -1)}"
            Log.e(TAG, error)
            _bluetoothState.value = _bluetoothState.value.copy(errorMessage = error)
            return false
        }

        return true
    }

    private fun performDiscoveryAttempt() {
        Log.d(TAG, "Performing discovery attempt ${retryCount + 1}")

        // Strategy 1: Standard discovery
        if (tryStandardDiscovery()) {
            return
        }

        // Strategy 2: Reset and retry
        if (retryCount < MAX_RETRY_ATTEMPTS) {
            Log.d(TAG, "Standard discovery failed, trying reset approach...")
            tryResetAndRetry()
            return
        }

        // Strategy 3: Fallback to paired devices only
        Log.w(TAG, "All discovery attempts failed, showing paired devices only")
        _bluetoothState.value = _bluetoothState.value.copy(
            errorMessage = "Discovery unavailable - showing paired devices only. Try restarting Bluetooth or the app.",
            lastDiscoveryMethod = "Fallback (Paired Only)"
        )
        updateDevicesList()
    }

    private fun tryStandardDiscovery(): Boolean {
        try {
            Log.d(TAG, "Attempting standard discovery...")
            val success = bluetoothAdapter?.startDiscovery() ?: false
            Log.d(TAG, "Standard discovery result: $success")

            if (success) {
                _bluetoothState.value = _bluetoothState.value.copy(
                    lastDiscoveryMethod = "Standard Discovery"
                )
                return true
            }

        } catch (e: Exception) {
            Log.e(TAG, "Exception during standard discovery", e)
        }
        return false
    }

    private fun tryResetAndRetry() {
        retryCount++

        Log.d(TAG, "Trying reset approach (attempt $retryCount)")

        // Force a brief disable/enable cycle to reset the adapter state
        handler.postDelayed({
            if (retryCount <= MAX_RETRY_ATTEMPTS) {
                Log.d(TAG, "Retrying discovery after reset delay...")

                // Try one more standard attempt
                val success = bluetoothAdapter?.startDiscovery() ?: false
                Log.d(TAG, "Retry discovery result: $success")

                if (!success) {
                    // Try again with longer delay
                    handler.postDelayed({
                        performDiscoveryAttempt()
                    }, RETRY_DELAY_MS * retryCount)
                } else {
                    _bluetoothState.value = _bluetoothState.value.copy(
                        lastDiscoveryMethod = "Retry Success (attempt $retryCount)"
                    )
                }
            }
        }, 500)
    }

    fun stopDiscovery() {
        Log.d(TAG, "Stopping discovery...")
        cancelDiscoveryTimeout()
        retryCount = 0

        if (hasRequiredPermissions() && bluetoothAdapter?.isDiscovering == true) {
            try {
                bluetoothAdapter.cancelDiscovery()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping discovery", e)
            }
        }
    }

    private fun cancelDiscoveryTimeout() {
        discoveryTimeoutRunnable?.let {
            handler.removeCallbacks(it)
            discoveryTimeoutRunnable = null
        }
    }

    fun getPairedDevices(): Set<BluetoothDevice> {
        return if (hasRequiredPermissions()) {
            try {
                val paired = bluetoothAdapter?.bondedDevices ?: emptySet()
                Log.d(TAG, "Found ${paired.size} paired devices")
                paired
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception getting paired devices", e)
                emptySet()
            }
        } else {
            Log.w(TAG, "Cannot get paired devices - permissions not granted")
            emptySet()
        }
    }

    private fun updateDevicesList() {
        val pairedDevices = getPairedDevices()
        val allDevices = (pairedDevices + discoveredDevices).distinctBy { it.address }

        Log.d(TAG, "Updating devices list: ${pairedDevices.size} paired + ${discoveredDevices.size} discovered = ${allDevices.size} total")

        _bluetoothState.value = _bluetoothState.value.copy(
            devices = allDevices,
            permissionsGranted = hasRequiredPermissions(),
            locationEnabled = isLocationEnabled(),
            adapterState = getBluetoothStateString(bluetoothAdapter?.state ?: -1)
        )
    }

    private fun getDeviceName(device: BluetoothDevice): String {
        return if (hasRequiredPermissions()) {
            try {
                device.name ?: "Unknown Device"
            } catch (e: SecurityException) {
                "Permission Required"
            }
        } else {
            "Permission Required"
        }
    }

    private fun getBluetoothStateString(state: Int): String {
        return when (state) {
            BluetoothAdapter.STATE_OFF -> "OFF"
            BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
            BluetoothAdapter.STATE_ON -> "ON"
            BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
            else -> "UNKNOWN($state)"
        }
    }

    private fun getScanModeString(scanMode: Int): String {
        return when (scanMode) {
            BluetoothAdapter.SCAN_MODE_NONE -> "NONE"
            BluetoothAdapter.SCAN_MODE_CONNECTABLE -> "CONNECTABLE"
            BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE -> "CONNECTABLE_DISCOVERABLE"
            else -> "UNKNOWN($scanMode)"
        }
    }

    fun registerReceiver() {
        if (isReceiverRegistered) {
            Log.w(TAG, "Receiver already registered")
            return
        }

        Log.d(TAG, "Registering Bluetooth receiver")

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED)
        }

        try {
            context.registerReceiver(bluetoothReceiver, filter)
            isReceiverRegistered = true

            // Initialize state
            _bluetoothState.value = BluetoothState(
                isEnabled = isBluetoothEnabled(),
                permissionsGranted = hasRequiredPermissions(),
                locationEnabled = isLocationEnabled(),
                devices = getPairedDevices().toList(),
                adapterState = getBluetoothStateString(bluetoothAdapter?.state ?: -1)
            )

            Log.d(TAG, "Receiver registered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register receiver", e)
            _bluetoothState.value = _bluetoothState.value.copy(
                errorMessage = "Failed to register Bluetooth receiver: ${e.message}"
            )
        }
    }

    fun unregisterReceiver() {
        if (!isReceiverRegistered) {
            return
        }

        try {
            cancelDiscoveryTimeout()
            stopDiscovery()
            context.unregisterReceiver(bluetoothReceiver)
            isReceiverRegistered = false
            Log.d(TAG, "Receiver unregistered successfully")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receiver was not registered")
        }
    }

    fun getBluetoothStatus(): String {
        return buildString {
            appendLine("=== BLUETOOTH DIAGNOSTICS ===")
            appendLine("Bluetooth supported: ${isBluetoothSupported()}")
            appendLine("Bluetooth enabled: ${isBluetoothEnabled()}")
            appendLine("Adapter state: ${getBluetoothStateString(bluetoothAdapter?.state ?: -1)}")
            appendLine("Currently discovering: ${bluetoothAdapter?.isDiscovering}")
            appendLine("Scan mode: ${getScanModeString(bluetoothAdapter?.scanMode ?: -1)}")
            appendLine("Location enabled: ${isLocationEnabled()}")
            appendLine("Permissions granted: ${hasRequiredPermissions()}")
            appendLine("Receiver registered: $isReceiverRegistered")
            appendLine("Discovery attempts: ${_bluetoothState.value.discoveryAttempts}")
            appendLine("Last method: ${_bluetoothState.value.lastDiscoveryMethod}")
            appendLine("Retry count: $retryCount")
            appendLine("Android version: ${Build.VERSION.SDK_INT}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")

            appendLine("\n=== DEVICE COUNTS ===")
            appendLine("Paired devices: ${getPairedDevices().size}")
            appendLine("Discovered devices: ${discoveredDevices.size}")
            appendLine("Total devices: ${_bluetoothState.value.devices.size}")
        }
    }

    fun forceRefresh() {
        Log.d(TAG, "Force refresh requested")
        retryCount = 0
        updateDevicesList()
    }

    // Force discovery reset - useful for troubleshooting
    fun resetAndRetryDiscovery() {
        Log.d(TAG, "Reset and retry discovery requested")
        retryCount = 0
        stopDiscovery()

        handler.postDelayed({
            startDiscovery()
        }, 1000)
    }

    // Alternative discovery method for problematic devices
    fun tryAlternativeDiscovery() {
        Log.d(TAG, "Trying alternative discovery approach...")

        // Some devices need their scan mode changed first
        try {
            // Request to make device discoverable (this sometimes helps with discovery)
            val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 1) // Minimal duration
            }

            _bluetoothState.value = _bluetoothState.value.copy(
                errorMessage = "Try: Go to your device's Bluetooth settings and make it discoverable, then scan again"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Alternative discovery failed", e)
        }
    }
}