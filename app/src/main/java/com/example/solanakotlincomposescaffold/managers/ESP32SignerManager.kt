package com.example.solanakotlincomposescaffold.managers

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbConstants
import android.util.Log
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

data class ESP32SignerState(
    val isConnected: Boolean = false,
    val esp32PublicKey: String? = null,
    val errorMessage: String? = null,
    val isWaitingForButtonPress: Boolean = false,
    val lastSignature: String? = null
)

@Singleton
class ESP32SignerManager @Inject constructor(
    private val usbManager: UsbManagerHelper
) {
    companion object {
        private const val TAG = "ESP32SignerManager"
        private const val TIMEOUT_MS = 5000
        private const val BUTTON_WAIT_TIMEOUT_MS = 30000 // 30 seconds for button press
    }

    private val _signerState = MutableStateFlow(ESP32SignerState())
    val signerState: StateFlow<ESP32SignerState> = _signerState

    private var currentConnection: UsbDeviceConnection? = null
    private var currentInterface: UsbInterface? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null
    private var currentDevice: UsbDevice? = null

    /**
     * Test function to find ESP32 device
     */
    suspend fun findESP32Device(): Boolean = withContext(Dispatchers.IO) {
        try {
            val devices = usbManager.getConnectedDevices()
            Log.d(TAG, "Found ${devices.size} USB devices")
            
            for (device in devices) {
                Log.d(TAG, "Device: ${device.deviceName}")
                Log.d(TAG, "Vendor ID: 0x${device.vendorId.toString(16).uppercase()}")
                Log.d(TAG, "Product ID: 0x${device.productId.toString(16).uppercase()}")
                
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    Log.d(TAG, "Product Name: ${device.productName}")
                    Log.d(TAG, "Manufacturer: ${device.manufacturerName}")
                }
            }

            val esp32Device = findESP32DeviceInList(devices)

            if (esp32Device != null) {
                currentDevice = esp32Device
                _signerState.value = _signerState.value.copy(
                    errorMessage = "Found ESP32: ${esp32Device.deviceName} (VID:0x${esp32Device.vendorId.toString(16).uppercase()}, PID:0x${esp32Device.productId.toString(16).uppercase()})"
                )
                true
            } else {
                _signerState.value = _signerState.value.copy(
                    errorMessage = "No ESP32 device found. Connect ESP32 via USB."
                )
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding ESP32", e)
            _signerState.value = _signerState.value.copy(
                errorMessage = "Error: ${e.message}"
            )
            false
        }
    }

    /**
     * Connect to ESP32 and establish communication
     */
    suspend fun connectToESP32(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (currentDevice == null) {
                if (!findESP32Device()) {
                    return@withContext false
                }
            }

            val device = currentDevice!!
            
            // Check permissions
            if (!usbManager.hasPermission(device)) {
                usbManager.requestPermission(device)
                _signerState.value = _signerState.value.copy(
                    errorMessage = "Requesting USB permission for ESP32..."
                )
                return@withContext false
            }

            // Establish USB connection
            if (!establishUSBConnection(device)) {
                _signerState.value = _signerState.value.copy(
                    errorMessage = "Failed to establish USB connection"
                )
                return@withContext false
            }

            // Get public key from ESP32
            val publicKey = getESP32PublicKey()
            if (publicKey != null) {
                _signerState.value = _signerState.value.copy(
                    isConnected = true,
                    esp32PublicKey = publicKey,
                    errorMessage = "Connected! ESP32 Public Key: ${publicKey.take(12)}...${publicKey.takeLast(12)}"
                )
                true
            } else {
                disconnect()
                _signerState.value = _signerState.value.copy(
                    errorMessage = "Connected to USB but failed to get ESP32 public key"
                )
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to ESP32", e)
            _signerState.value = _signerState.value.copy(
                errorMessage = "Connection error: ${e.message}"
            )
            false
        }
    }

    /**
     * Sign a transaction with ESP32 (requires button press)
     */
    suspend fun signTransaction(messageBytes: ByteArray): String? = withContext(Dispatchers.IO) {
        if (!_signerState.value.isConnected) {
            _signerState.value = _signerState.value.copy(
                errorMessage = "ESP32 not connected"
            )
            return@withContext null
        }

        try {
            _signerState.value = _signerState.value.copy(
                isWaitingForButtonPress = true,
                errorMessage = "Press the BOOT button on ESP32 to sign..."
            )

            val base64Message = Base64.encodeToString(messageBytes, Base64.NO_WRAP)
            val signCommand = "SIGN:$base64Message"
            
            Log.d(TAG, "Sending sign command: $signCommand")
            
            if (!sendCommand(signCommand)) {
                _signerState.value = _signerState.value.copy(
                    isWaitingForButtonPress = false,
                    errorMessage = "Failed to send sign command"
                )
                return@withContext null
            }

            // Wait for signature response (this will block until button is pressed)
            val response = readResponse(BUTTON_WAIT_TIMEOUT_MS)
            
            _signerState.value = _signerState.value.copy(
                isWaitingForButtonPress = false
            )

            if (response?.startsWith("SIGNATURE:") == true) {
                val signature = response.substring(10)
                _signerState.value = _signerState.value.copy(
                    lastSignature = signature,
                    errorMessage = "Transaction signed successfully!"
                )
                Log.d(TAG, "Received signature: $signature")
                signature
            } else {
                _signerState.value = _signerState.value.copy(
                    errorMessage = "Invalid signature response: $response"
                )
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sign transaction", e)
            _signerState.value = _signerState.value.copy(
                isWaitingForButtonPress = false,
                errorMessage = "Signing failed: ${e.message}"
            )
            null
        }
    }

    /**
    * Get public key from ESP32 with enhanced debugging
    */
    private suspend fun getESP32PublicKey(): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "=== Starting public key request ===")
            
            // Wait a bit for ESP32 to be ready
            delay(1000)
            
            // Try sending command multiple times
            var success = false
            for (attempt in 1..3) {
                Log.d(TAG, "Attempt $attempt: Sending GET_PUBKEY command")
                success = sendCommand("GET_PUBKEY")
                if (success) break
                delay(500)
            }
            
            if (!success) {
                Log.e(TAG, "Failed to send GET_PUBKEY command after 3 attempts")
                return@withContext null
            }

            Log.d(TAG, "Command sent successfully, waiting for response...")
            
            // Try reading with longer timeout and more attempts
            var response: String? = null
            for (attempt in 1..5) {
                Log.d(TAG, "Read attempt $attempt")
                response = readResponse(3000) // 3 second timeout per attempt
                if (response != null) {
                    Log.d(TAG, "Got response on attempt $attempt: $response")
                    break
                }
                delay(500)
            }
            
            if (response == null) {
                Log.e(TAG, "No response received after 5 attempts")
                return@withContext null
            }
            
            if (response.startsWith("PUBKEY:")) {
                val publicKey = response.substring(7)
                Log.d(TAG, "Successfully extracted public key: $publicKey")
                publicKey
            } else {
                Log.e(TAG, "Response doesn't start with PUBKEY: '$response'")
                // Let's see what we actually got
                Log.e(TAG, "Response bytes: ${response.toByteArray().joinToString { "%02x".format(it) }}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in getESP32PublicKey", e)
            null
        }
    }

    // Also update the sendCommand method to be more verbose:
    /**
    * Send command to ESP32 with enhanced debugging
    */
    private fun sendCommand(command: String): Boolean {
        return try {
            val data = (command + "\n").toByteArray()
            Log.d(TAG, "Sending command: '$command' (${data.size} bytes)")
            Log.d(TAG, "Command bytes: ${data.joinToString { "%02x".format(it) }}")
            
            val bytesTransferred = currentConnection?.bulkTransfer(outEndpoint, data, data.size, TIMEOUT_MS)
            val success = bytesTransferred == data.size
            
            Log.d(TAG, "Send result: $success ($bytesTransferred/${data.size} bytes transferred)")
            
            if (!success) {
                Log.e(TAG, "Failed to send complete command")
            }
            
            success
        } catch (e: Exception) {
            Log.e(TAG, "Exception in sendCommand", e)
            false
        }
    }

    // And update readResponse to be more verbose:
    /**
    * Read response from ESP32 with enhanced debugging
    */
    private suspend fun readResponse(timeoutMs: Int): String? = withContext(Dispatchers.IO) {
        try {
            val buffer = ByteArray(1024)
            var totalBytesRead = 0
            val startTime = System.currentTimeMillis()
            
            Log.d(TAG, "Starting to read response (timeout: ${timeoutMs}ms)")
            
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                val bytesRead = currentConnection?.bulkTransfer(inEndpoint, buffer, totalBytesRead, buffer.size - totalBytesRead, 1000)
                
                if (bytesRead != null && bytesRead > 0) {
                    Log.d(TAG, "Read $bytesRead bytes (total: ${totalBytesRead + bytesRead})")
                    totalBytesRead += bytesRead
                    
                    // Log the raw bytes we received
                    val currentData = buffer.sliceArray(0 until totalBytesRead)
                    Log.d(TAG, "Current buffer: ${String(currentData)} (hex: ${currentData.joinToString { "%02x".format(it) }})")
                    
                    // Check if we have a complete line (ending with \n)
                    val response = String(buffer, 0, totalBytesRead)
                    val newlineIndex = response.indexOf('\n')
                    if (newlineIndex >= 0) {
                        val result = response.substring(0, newlineIndex).trim()
                        Log.d(TAG, "Complete response received: '$result'")
                        return@withContext result
                    }
                } else if (bytesRead == null) {
                    Log.d(TAG, "bulkTransfer returned null")
                } else {
                    Log.d(TAG, "bulkTransfer returned 0 bytes")
                }
                
                // Small delay to prevent busy waiting
                delay(100)
            }
            
            Log.e(TAG, "Response timeout after ${timeoutMs}ms. Total bytes read: $totalBytesRead")
            if (totalBytesRead > 0) {
                val partialData = String(buffer, 0, totalBytesRead)
                Log.e(TAG, "Partial data received: '$partialData'")
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Exception in readResponse", e)
            null
        }
    }

    /**
     * Find ESP32 device in the list
     */
    private fun findESP32DeviceInList(devices: List<UsbDevice>): UsbDevice? {
        // First try to find by vendor/product ID for common ESP32 USB chips
        var esp32Device = devices.find { device ->
            (device.vendorId == 0x10C4 && device.productId == 0xEA60) || // CP210x
            (device.vendorId == 0x1A86 && device.productId == 0x7523) || // CH340
            (device.vendorId == 0x0403) // FTDI
        }

        // If not found, look for any device that might be ESP32
        if (esp32Device == null) {
            esp32Device = devices.find { device ->
                val deviceName = device.deviceName.lowercase()
                val productName = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    device.productName?.lowercase() ?: ""
                } else {
                    ""
                }
                
                deviceName.contains("ttyusb") || 
                deviceName.contains("ttyacm") ||
                deviceName.contains("usbserial") ||
                productName.contains("cp210") ||
                productName.contains("ch340") ||
                productName.contains("serial")
            }
        }

        return esp32Device
    }

    /**
     * Establish USB connection
     */
    private fun establishUSBConnection(device: UsbDevice): Boolean {
        try {
            currentConnection = usbManager.getUsbManager().openDevice(device)
            if (currentConnection == null) {
                Log.e(TAG, "Failed to open USB device")
                return false
            }

            // Find the right interface (usually interface 0 for serial)
            currentInterface = device.getInterface(0)
            if (currentInterface == null) {
                Log.e(TAG, "No USB interface found")
                return false
            }

            if (!currentConnection!!.claimInterface(currentInterface, true)) {
                Log.e(TAG, "Failed to claim USB interface")
                return false
            }

            // Find bulk endpoints
            for (i in 0 until currentInterface!!.endpointCount) {
                val endpoint = currentInterface!!.getEndpoint(i)
                if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                        inEndpoint = endpoint
                    } else {
                        outEndpoint = endpoint
                    }
                }
            }

            if (inEndpoint == null || outEndpoint == null) {
                Log.e(TAG, "Failed to find bulk endpoints")
                return false
            }

            Log.d(TAG, "USB connection established successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish USB connection", e)
            return false
        }
    }

    /**
     * Disconnect from ESP32
     */
    fun disconnect() {
        try {
            currentInterface?.let { currentConnection?.releaseInterface(it) }
            currentConnection?.close()
            currentConnection = null
            currentInterface = null
            inEndpoint = null
            outEndpoint = null
            currentDevice = null
            
            _signerState.value = ESP32SignerState()
            Log.d(TAG, "Disconnected from ESP32")
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect", e)
        }
    }

    /**
     * Clear any error messages
     */
    fun clearError() {
        _signerState.value = _signerState.value.copy(errorMessage = null)
    }

    /**
     * Check if ESP32 is connected
     */
    fun isConnected(): Boolean = _signerState.value.isConnected

    /**
     * Get current ESP32 public key
     */
    fun getPublicKey(): String? = _signerState.value.esp32PublicKey
}