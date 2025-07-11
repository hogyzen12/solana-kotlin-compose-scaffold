package com.example.solanakotlincomposescaffold

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.solanakotlincomposescaffold.managers.BluetoothManagerHelper
import com.example.solanakotlincomposescaffold.managers.UsbManagerHelper
import com.example.solanakotlincomposescaffold.managers.ESP32SignerManager
import com.example.solanakotlincomposescaffold.ui.theme.SolanaKotlinComposeScaffoldTheme
import com.example.solanakotlincomposescaffold.viewmodel.MainViewModel
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import dagger.hilt.android.AndroidEntryPoint
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.platform.LocalContext
import com.solana.publickey.SolanaPublicKey
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.funkatronics.encoders.Base58
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    @Inject
    lateinit var bluetoothManager: BluetoothManagerHelper

    @Inject
    lateinit var usbManager: UsbManagerHelper

    @Inject
    lateinit var esp32SignerManager: ESP32SignerManager

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d(TAG, "Bluetooth enable result: ${result.resultCode}")
        if (result.resultCode == RESULT_OK) {
            // Bluetooth was enabled, refresh state
            bluetoothManager.forceRefresh()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        Log.d(TAG, "Permission results: $permissions")
        val allGranted = permissions.values.all { it }

        if (allGranted) {
            Log.d(TAG, "All permissions granted, registering receiver")
            bluetoothManager.registerReceiver()
        } else {
            Log.w(TAG, "Not all permissions granted")
            val deniedPermissions = permissions.filterValues { !it }.keys
            Log.w(TAG, "Denied permissions: $deniedPermissions")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "MainActivity onCreate")

        val sender = ActivityResultSender(this)

        setContent {
            SolanaKotlinComposeScaffoldTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(sender, bluetoothManager, usbManager, esp32SignerManager)
                }
            }
        }

        // Check and request permissions
        checkAndRequestPermissions()

        // Register USB manager receiver
        usbManager.registerReceiver()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MainActivity onDestroy")
        bluetoothManager.unregisterReceiver()
        usbManager.unregisterReceiver()
    }

    private fun checkAndRequestPermissions() {
        Log.d(TAG, "Checking permissions...")

        val requiredPermissions = bluetoothManager.getRequiredPermissions()
        Log.d(TAG, "Required permissions: $requiredPermissions")

        val permissionsToRequest = requiredPermissions.filter {
            val granted = ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Permission $it: $granted")
            !granted
        }

        if (permissionsToRequest.isNotEmpty()) {
            Log.d(TAG, "Requesting permissions: $permissionsToRequest")
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            Log.d(TAG, "All permissions already granted, registering receiver")
            bluetoothManager.registerReceiver()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
fun MainScreen(
    intentSender: ActivityResultSender? = null,
    bluetoothManager: BluetoothManagerHelper? = null,
    usbManager: UsbManagerHelper? = null,
    esp32SignerManager: ESP32SignerManager? = null,
    viewModel: MainViewModel = hiltViewModel()
) {
    val viewState by viewModel.viewState.collectAsState()
    val bluetoothState by (bluetoothManager?.bluetoothState?.collectAsState() ?: remember { mutableStateOf(null) })
    val usbState by (usbManager?.usbState?.collectAsState() ?: remember { mutableStateOf(null) })
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var showBluetoothDebug by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Text(
                text = "Solana Compose dApp Scaffold",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(all = 24.dp)
            )
        },
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        contentWindowInsets = WindowInsets(12, 12, 12, 12),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->

        LaunchedEffect(Unit) {
            viewModel.loadConnection()
        }

        LaunchedEffect(viewState.snackbarMessage) {
            viewState.snackbarMessage?.let { message ->
                snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
                viewModel.clearSnackBar()
            }
        }

        LazyColumn(
            modifier = Modifier.padding(padding)
        ) {
            // Messages Section
            item {
                Section(sectionTitle = "Messages:") {
                    Button(
                        onClick = {
                            if (intentSender != null && viewState.canTransact)
                                viewModel.signMessage(intentSender, "Hello Solana!")
                            else
                                viewModel.disconnect()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "Sign a message")
                    }
                }
            }

            // Transactions Section
            item {
                Section(sectionTitle = "Transactions:") {
                    Button(
                        onClick = {
                            if (intentSender != null && viewState.canTransact)
                                viewModel.signTransaction(intentSender)
                            else
                                viewModel.disconnect()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "Sign a Transaction (deprecated)")
                    }
                    Button(
                        onClick = {
                            if (intentSender != null && viewState.canTransact)
                                viewModel.publishMemo(intentSender, "Hello Solana!")
                            else
                                viewModel.disconnect()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "Send a Memo Transaction")
                    }

                    val memoTxSignature = viewState.memoTxSignature
                    if (memoTxSignature != null) {
                        ExplorerHyperlink(memoTxSignature)
                    }
                }
            }

            // ESP32 Hardware Signer Section
            item {
                Section(sectionTitle = "ESP32 Hardware Signer:") {
                    val esp32State by (esp32SignerManager?.signerState?.collectAsState() ?: remember { mutableStateOf(null) })
                    
                    esp32State?.let { state ->
                        // Connection status
                        if (state.isConnected) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                colors = androidx.compose.material3.CardDefaults.cardColors(
                                    containerColor = Color.Green.copy(alpha = 0.1f)
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp)
                                ) {
                                    Text(
                                        "ESP32 Connected ✅",
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Green
                                    )
                                    state.esp32PublicKey?.let { pubkey ->
                                        Text(
                                            "Public Key: ${pubkey.take(12)}...${pubkey.takeLast(12)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                    if (state.isWaitingForButtonPress) {
                                        Text(
                                            "📱 Press the BOOT button on ESP32 to confirm...",
                                            color = Color.Blue,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.padding(top = 8.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Status messages
                        if (state.errorMessage != null) {
                            Text(
                                text = state.errorMessage,
                                color = when {
                                    state.errorMessage.contains("Error") || state.errorMessage.contains("Failed") -> Color.Red
                                    state.errorMessage.contains("Connected") || state.errorMessage.contains("signed") -> Color.Green
                                    else -> Color.Blue
                                },
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }

                        // Action buttons
                        if (state.isConnected) {
                            // ESP32 is connected - show signing options
                            Column {
                                Button(
                                    onClick = {
                                        esp32SignerManager?.let { manager ->
                                            coroutineScope.launch {
                                                val testMessage = "Hello from ESP32!".toByteArray()
                                                manager.signTransaction(testMessage)
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !state.isWaitingForButtonPress
                                ) {
                                    Text(if (state.isWaitingForButtonPress) "Waiting for button press..." else "Test Sign Message")
                                }

                                Button(
                                    onClick = {
                                        esp32SignerManager?.disconnect()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                        containerColor = Color.Red
                                    )
                                ) {
                                    Text("Disconnect ESP32")
                                }
                            }
                        } else {
                            // ESP32 not connected - show connection options
                            Column {
                                Button(
                                    onClick = {
                                        esp32SignerManager?.let { manager ->
                                            manager.clearError()
                                            coroutineScope.launch {
                                                manager.findESP32Device()
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Find ESP32 Device")
                                }

                                Button(
                                    onClick = {
                                        esp32SignerManager?.let { manager ->
                                            manager.clearError()
                                            coroutineScope.launch {
                                                manager.connectToESP32()
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Connect to ESP32")
                                }
                            }
                        }

                        // Show last signature if available
                        state.lastSignature?.let { signature ->
                            Text(
                                "Last Signature: ${signature.take(16)}...",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            }

            // Enhanced Bluetooth Section with Troubleshooting
            item {
                Section(sectionTitle = "Bluetooth:") {
                    bluetoothState?.let { state ->
                        Text("Bluetooth Enabled: ${state.isEnabled}")
                        Text("Scanning: ${state.isScanning}")
                        Text("Permissions: ${state.permissionsGranted}")
                        Text("Location: ${state.locationEnabled}")
                        Text("Devices Found: ${state.devices.size}")
                        Text("Adapter State: ${state.adapterState}")

                        if (state.discoveryAttempts > 0) {
                            Text("Discovery Attempts: ${state.discoveryAttempts}")
                            Text("Last Method: ${state.lastDiscoveryMethod}")
                        }

                        if (state.errorMessage != null) {
                            Text(
                                text = "Status: ${state.errorMessage}",
                                color = if (state.errorMessage.contains("Error") ||
                                    state.errorMessage.contains("failed")) Color.Red else Color.Blue,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }

                        // Debug toggle button
                        Button(
                            onClick = { showBluetoothDebug = !showBluetoothDebug },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (showBluetoothDebug) "Hide Debug Info" else "Show Debug Info")
                        }

                        // Debug information
                        if (showBluetoothDebug) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = bluetoothManager?.getBluetoothStatus() ?: "Debug info unavailable",
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                        }

                        // Primary action buttons
                        Row {
                            Button(
                                onClick = {
                                    if (!state.isEnabled) {
                                        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                                        if (ActivityCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.BLUETOOTH_CONNECT
                                            ) == PackageManager.PERMISSION_GRANTED ||
                                            Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                                        ) {
                                            context.startActivity(enableBtIntent)
                                        }
                                    } else {
                                        Log.d("MainActivity", "Starting Bluetooth discovery")
                                        bluetoothManager?.startDiscovery()
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 4.dp)
                            ) {
                                Text(if (state.isEnabled) "Scan Devices" else "Enable Bluetooth")
                            }

                            if (state.isScanning) {
                                Button(
                                    onClick = { bluetoothManager?.stopDiscovery() },
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 4.dp)
                                ) {
                                    Text("Stop Scan")
                                }
                            } else {
                                Button(
                                    onClick = { bluetoothManager?.forceRefresh() },
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 4.dp)
                                ) {
                                    Text("Refresh")
                                }
                            }
                        }

                        // Troubleshooting buttons
                        if (state.errorMessage?.contains("returned false") == true) {
                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                "Troubleshooting Options:",
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            Row {
                                Button(
                                    onClick = { bluetoothManager?.resetAndRetryDiscovery() },
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 4.dp)
                                ) {
                                    Text("Reset & Retry")
                                }

                                Button(
                                    onClick = { bluetoothManager?.tryAlternativeDiscovery() },
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 4.dp)
                                ) {
                                    Text("Alternative")
                                }
                            }

                            Text(
                                "Tips:\n• Make another device discoverable\n• Restart Bluetooth in Settings\n• Try scanning from Settings first",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }

                        if (state.devices.isNotEmpty()) {
                            Text(
                                "Found Devices (${state.devices.size}):",
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        } else if (state.isEnabled && !state.isScanning) {
                            Text(
                                "No devices found. Make sure other devices are discoverable (Bluetooth settings open).",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            }

            // USB Section (keeping your existing implementation)
            item {
                Section(sectionTitle = "USB Devices:") {
                    usbState?.let { state ->
                        if (state.errorMessage != null) {
                            Text(
                                text = state.errorMessage,
                                color = if (state.errorMessage.contains("Error") ||
                                    state.errorMessage.contains("denied")) Color.Red else Color.Blue
                            )
                        }

                        Text("Connected USB Devices: ${state.devices.size}")

                        state.connectedDevice?.let { device ->
                            Text(
                                "Active Connection: ${device.deviceName}",
                                color = Color.Green,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Button(
                            onClick = {
                                // Refresh USB device list
                                // This will automatically update through the state flow
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Refresh USB Devices")
                        }
                    }
                }
            }

            // USB devices list
            usbState?.devices?.let { devices ->
                items(devices) { device ->
                    UsbDeviceCard(device, usbManager)
                }
            }

            // Account info and connection buttons
            item {
                Spacer(modifier = Modifier.height(16.dp))

                if (viewState.canTransact) {
                    AccountInfo(
                        walletName = viewState.userLabel,
                        address = viewState.userAddress,
                        balance = viewState.solBalance
                    )
                }

                Row {
                    if (viewState.canTransact) {
                        Button(
                            onClick = {
                                viewModel.requestAirdrop(SolanaPublicKey(Base58.decode(viewState.userAddress)))
                            },
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 4.dp)
                                .fillMaxWidth()
                        ) {
                            Text("Request Airdrop")
                        }
                    }
                    Button(
                        onClick = {
                            if (intentSender != null && !viewState.canTransact)
                                viewModel.connect(intentSender)
                            else
                                viewModel.disconnect()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp)
                            .fillMaxWidth()
                    ) {
                        Text(if (viewState.canTransact) "Disconnect" else "Connect")
                    }
                }
            }
        }
    }
}

@Composable
fun BluetoothDeviceCard(device: android.bluetooth.BluetoothDevice) {
    Card(
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .padding(vertical = 4.dp)
            .fillMaxWidth()
            .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            val context = LocalContext.current
            val deviceName = if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                device.name ?: "Unknown Device"
            } else {
                "Permission Required"
            }

            Text(
                text = deviceName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = device.address,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )
            Text(
                text = "Bond State: ${getBondStateString(device.bondState)}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Type: ${getDeviceTypeString(device.type)}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun UsbDeviceCard(device: android.hardware.usb.UsbDevice, usbManager: UsbManagerHelper?) {
    Card(
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .padding(vertical = 4.dp)
            .fillMaxWidth()
            .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = device.deviceName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                device.productName?.let { productName ->
                    Text(
                        text = "Product: $productName",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                device.manufacturerName?.let { manufacturer ->
                    Text(
                        text = "Manufacturer: $manufacturer",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )
                }
            }

            Text(
                text = "Vendor ID: ${device.vendorId}, Product ID: ${device.productId}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )

            val hasPermission = usbManager?.hasPermission(device) ?: false
            Text(
                text = "Permission: ${if (hasPermission) "Granted" else "Not Granted"}",
                style = MaterialTheme.typography.bodySmall,
                color = if (hasPermission) Color.Green else Color.Red
            )

            Button(
                onClick = {
                    usbManager?.connectToDevice(device)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (hasPermission) "Connect" else "Request Permission")
            }
        }
    }
}

private fun getBondStateString(bondState: Int): String {
    return when (bondState) {
        android.bluetooth.BluetoothDevice.BOND_NONE -> "Not Paired"
        android.bluetooth.BluetoothDevice.BOND_BONDING -> "Pairing..."
        android.bluetooth.BluetoothDevice.BOND_BONDED -> "Paired"
        else -> "Unknown"
    }
}

private fun getDeviceTypeString(deviceType: Int): String {
    return when (deviceType) {
        android.bluetooth.BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic"
        android.bluetooth.BluetoothDevice.DEVICE_TYPE_LE -> "Low Energy"
        android.bluetooth.BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual Mode"
        else -> "Unknown"
    }
}

@Composable
fun Section(sectionTitle: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
        Text(
            text = sectionTitle,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        content()
    }
}

@Composable
fun AccountInfo(walletName: String, address: String, balance: Number) {
    Card(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .padding(bottom = 8.dp)
            .fillMaxWidth()
            .border(1.dp, Color.Black, RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Connected Wallet",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "$walletName ($address)",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "$balance SOL",
                style = MaterialTheme.typography.headlineLarge
            )
        }
    }
}

@Composable
fun ExplorerHyperlink(txSignature: String) {
    val context = LocalContext.current
    val url = "https://explorer.solana.com/tx/${txSignature}?cluster=devnet"
    val annotatedText = AnnotatedString.Builder("View your memo on the ").apply {
        pushStyle(
            SpanStyle(
                color = Color.Blue,
                textDecoration = TextDecoration.Underline,
                fontSize = 16.sp
            )
        )
        append("explorer.")
    }

    ClickableText(
        text = annotatedText.toAnnotatedString(),
        onClick = {
            openUrl(context, url)
        }
    )
}

fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    context.startActivity(intent)
}