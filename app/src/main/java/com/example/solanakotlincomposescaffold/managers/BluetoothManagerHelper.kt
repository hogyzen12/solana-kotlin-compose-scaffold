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
import android.os.Build
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class BluetoothState(
    val isEnabled: Boolean = false,
    val isScanning: Boolean = false,
    val devices: List<BluetoothDevice> = emptyList(),
    val errorMessage: String? = null
)

@Singleton
class BluetoothManagerHelper @Inject constructor(
    private val context: Context
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val _bluetoothState = MutableStateFlow(BluetoothState())
    val bluetoothState: StateFlow<BluetoothState> = _bluetoothState

    private val discoveredDevices = mutableSetOf<BluetoothDevice>()

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    device?.let {
                        discoveredDevices.add(it)
                        updateDevicesList()
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    _bluetoothState.value = _bluetoothState.value.copy(isScanning = true, errorMessage = null)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _bluetoothState.value = _bluetoothState.value.copy(isScanning = false)
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    _bluetoothState.value = _bluetoothState.value.copy(
                        isEnabled = state == BluetoothAdapter.STATE_ON
                    )
                }
            }
        }
    }

    fun isBluetoothSupported(): Boolean = bluetoothAdapter != null

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    fun startDiscovery() {
        if (!hasRequiredPermissions()) {
            _bluetoothState.value = _bluetoothState.value.copy(
                errorMessage = "Bluetooth permissions not granted"
            )
            return
        }

        if (!isBluetoothEnabled()) {
            _bluetoothState.value = _bluetoothState.value.copy(
                errorMessage = "Bluetooth is not enabled"
            )
            return
        }

        discoveredDevices.clear()
        bluetoothAdapter?.startDiscovery()
    }

    fun stopDiscovery() {
        if (hasRequiredPermissions()) {
            bluetoothAdapter?.cancelDiscovery()
        }
    }

    fun getPairedDevices(): Set<BluetoothDevice> {
        return if (hasRequiredPermissions()) {
            bluetoothAdapter?.bondedDevices ?: emptySet()
        } else {
            emptySet()
        }
    }

    private fun updateDevicesList() {
        val allDevices = (getPairedDevices() + discoveredDevices).toList()
        _bluetoothState.value = _bluetoothState.value.copy(devices = allDevices)
    }

    fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        context.registerReceiver(bluetoothReceiver, filter)

        // Initial state
        _bluetoothState.value = BluetoothState(
            isEnabled = isBluetoothEnabled(),
            devices = getPairedDevices().toList()
        )
    }

    fun unregisterReceiver() {
        try {
            context.unregisterReceiver(bluetoothReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver was not registered
        }
    }
}