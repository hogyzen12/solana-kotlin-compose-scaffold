package com.example.solanakotlincomposescaffold.screens

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.solanakotlincomposescaffold.managers.BluetoothManagerHelper
import com.example.solanakotlincomposescaffold.managers.UsbManagerHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothUsbTestScreen(
    bluetoothManager: BluetoothManagerHelper,
    usbManager: UsbManagerHelper
) {
    val bluetoothState by bluetoothManager.bluetoothState.collectAsState()
    val usbState by usbManager.usbState.collectAsState()
    val context = LocalContext.current

    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* Handle result */ }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Bluetooth & USB Test",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // Bluetooth Status Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Bluetooth Status",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                StatusRow("Supported", bluetoothManager.isBluetoothSupported().toString())
                StatusRow("Enabled", bluetoothState.isEnabled.toString())
                StatusRow("Scanning", bluetoothState.isScanning.toString())
                StatusRow("Permissions", bluetoothManager.hasRequiredPermissions().toString())
                StatusRow("Devices Found", bluetoothState.devices.size.toString())

                if (bluetoothState.errorMessage != null) {
                    Text(
                        text = "Error: ${bluetoothState.errorMessage}",
                        color = Color.Red,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row {
                    Button(
                        onClick = {
                            if (!bluetoothState.isEnabled) {
                                val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                                if (ActivityCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.BLUETOOTH_CONNECT
                                    ) == PackageManager.PERMISSION_GRANTED ||
                                    Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                                ) {
                                    enableBluetoothLauncher.launch(enableIntent)
                                }
                            }
                        },
                        enabled = !bluetoothState.isEnabled,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    ) {
                        Text("Enable")
                    }

                    Button(
                        onClick = { bluetoothManager.startDiscovery() },
                        enabled = bluetoothState.isEnabled && !bluetoothState.isScanning,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                    ) {
                        Text("Scan")
                    }

                    Button(
                        onClick = { bluetoothManager.stopDiscovery() },
                        enabled = bluetoothState.isScanning,
                        modifier = Modifier.weight(1f).padding(start = 8.dp)
                    ) {
                        Text("Stop")
                    }
                }
            }
        }

        // USB Status Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "USB Status",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                StatusRow("Connected Devices", usbState.devices.size.toString())
                StatusRow("Active Connection", (usbState.connectedDevice?.deviceName ?: "None"))
                StatusRow("Has Permission", usbState.hasPermission.toString())

                usbState.errorMessage?.let { errorMessage ->
                    Text(
                        text = errorMessage,
                        color = if (errorMessage.contains("Error")) Color.Red else Color.Blue,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        // Device Lists
        LazyColumn {
            if (bluetoothState.devices.isNotEmpty()) {
                item {
                    Text(
                        text = "Bluetooth Devices",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                items(bluetoothState.devices) { device ->
                    DeviceListItem(
                        name = if (ActivityCompat.checkSelfPermission(
                                context,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) == PackageManager.PERMISSION_GRANTED ||
                            Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                        ) {
                            device.name ?: "Unknown"
                        } else {
                            "Permission Required"
                        },
                        address = device.address,
                        type = "Bluetooth"
                    )
                }
            }

            if (usbState.devices.isNotEmpty()) {
                item {
                    Text(
                        text = "USB Devices",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                items(usbState.devices) { device ->
                    DeviceListItem(
                        name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            device.productName ?: device.deviceName
                        } else {
                            device.deviceName
                        },
                        address = "VID:${device.vendorId} PID:${device.productId}",
                        type = "USB",
                        onClick = { usbManager.connectToDevice(device) }
                    )
                }
            }
        }
    }
}

@Composable
fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = "$label:")
        Text(
            text = value,
            fontWeight = FontWeight.Medium,
            color = when (value.lowercase()) {
                "true" -> Color.Green
                "false" -> Color.Red
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListItem(
    name: String,
    address: String,
    type: String,
    onClick: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        onClick = { onClick?.invoke() }
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = address,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }

                Surface(
                    color = if (type == "Bluetooth") Color.Blue else Color.Green,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = type,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}