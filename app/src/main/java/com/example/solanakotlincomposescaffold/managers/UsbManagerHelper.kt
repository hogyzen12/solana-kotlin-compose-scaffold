package com.example.solanakotlincomposescaffold.managers

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class UsbState(
    val devices: List<UsbDevice> = emptyList(),
    val connectedDevice: UsbDevice? = null,
    val errorMessage: String? = null,
    val hasPermission: Boolean = false
)

@Singleton
class UsbManagerHelper @Inject constructor(
    private val context: Context
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _usbState = MutableStateFlow(UsbState())
    val usbState: StateFlow<UsbState> = _usbState

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.solanakotlincomposescaffold.USB_PERMISSION"
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }

                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.let {
                                _usbState.value = _usbState.value.copy(
                                    connectedDevice = it,
                                    hasPermission = true,
                                    errorMessage = null
                                )
                            }
                        } else {
                            _usbState.value = _usbState.value.copy(
                                errorMessage = "USB permission denied for device: ${device?.deviceName}"
                            )
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    device?.let {
                        updateDevicesList()
                        _usbState.value = _usbState.value.copy(
                            errorMessage = "USB device attached: ${it.deviceName}"
                        )
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    updateDevicesList()
                    if (device == _usbState.value.connectedDevice) {
                        _usbState.value = _usbState.value.copy(
                            connectedDevice = null,
                            hasPermission = false,
                            errorMessage = "USB device detached: ${device?.deviceName}"
                        )
                    }
                }
            }
        }
    }

    fun getUsbManager(): UsbManager {
        return usbManager
    }

    fun getConnectedDevices(): List<UsbDevice> {
        return usbManager.deviceList.values.toList()
    }

    fun requestPermission(device: UsbDevice) {
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
            }
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    fun connectToDevice(device: UsbDevice): Boolean {
        return if (hasPermission(device)) {
            try {
                val connection = usbManager.openDevice(device)
                if (connection != null) {
                    _usbState.value = _usbState.value.copy(
                        connectedDevice = device,
                        hasPermission = true,
                        errorMessage = null
                    )
                    // Store connection for later use if needed
                    // You might want to keep this connection open for communication
                    connection.close() // Close for now, reopen when needed
                    true
                } else {
                    _usbState.value = _usbState.value.copy(
                        errorMessage = "Failed to open USB device: ${device.deviceName}"
                    )
                    false
                }
            } catch (e: Exception) {
                _usbState.value = _usbState.value.copy(
                    errorMessage = "Error connecting to USB device: ${e.message}"
                )
                false
            }
        } else {
            requestPermission(device)
            false
        }
    }

    private fun updateDevicesList() {
        _usbState.value = _usbState.value.copy(devices = getConnectedDevices())
    }

    fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        context.registerReceiver(usbReceiver, filter)

        // Initial state
        updateDevicesList()
    }

    fun unregisterReceiver() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver was not registered
        }
    }

    fun getDeviceInfo(device: UsbDevice): String {
        return buildString {
            appendLine("Device Name: ${device.deviceName}")
            appendLine("Vendor ID: ${device.vendorId}")
            appendLine("Product ID: ${device.productId}")
            appendLine("Device Class: ${device.deviceClass}")
            appendLine("Device Subclass: ${device.deviceSubclass}")
            appendLine("Device Protocol: ${device.deviceProtocol}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                appendLine("Manufacturer: ${device.manufacturerName ?: "Unknown"}")
                appendLine("Product: ${device.productName ?: "Unknown"}")
                appendLine("Serial: ${device.serialNumber ?: "Unknown"}")
            }
            appendLine("Interface Count: ${device.interfaceCount}")
        }
    }
}