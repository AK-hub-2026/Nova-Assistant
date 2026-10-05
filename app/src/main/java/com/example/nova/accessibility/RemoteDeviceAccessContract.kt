package com.example.nova.accessibility

/**
 * M11-D — Clean architecture and extension contracts for screen access
 * and future authorized remote-device integration.
 *
 * Requirements:
 * - 0% fake remote device control.
 * - 0% silent screen capture.
 * - Current device: AccessibilityService semantic screen information is primary.
 *   MediaProjection / visual capture requires explicit user consent and runtime grant.
 * - Remote device: Explicit pairing, cryptographic authorization, mutual authentication,
 *   clear user consent, revocation, and truthful connection states.
 */
enum class RemoteDeviceConnectionState(val displayLabel: String) {
    DISCONNECTED("Disconnected"),
    PAIRING_REQUESTED("Pairing Requested — User Confirmation Required"),
    AUTHENTICATING("Authenticating Device Certificate"),
    CONNECTED_AUTHORIZED("Connected & Authorized"),
    PERMISSION_REQUIRED("Remote Access Permission Required"),
    REVOKED("Access Revoked by User"),
    UNAVAILABLE("Remote Access Unavailable on this Network")
}

data class RemoteDeviceDescriptor(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String, // "LAPTOP", "DESKTOP", "SECONDARY_PHONE"
    val connectionState: RemoteDeviceConnectionState,
    val isPaired: Boolean,
    val lastVerifiedAtMs: Long?,
    val authorizedCapabilities: List<String>
)

interface RemoteDeviceAccessContract {
    val pairedDevices: List<RemoteDeviceDescriptor>
    fun initiatePairing(device: RemoteDeviceDescriptor): RemoteDeviceConnectionState
    fun revokeAccess(deviceId: String): Boolean
    fun verifyConnection(deviceId: String): RemoteDeviceConnectionState
}

object DefaultRemoteDeviceAccessManager : RemoteDeviceAccessContract {
    override val pairedDevices: List<RemoteDeviceDescriptor> = emptyList()

    override fun initiatePairing(device: RemoteDeviceDescriptor): RemoteDeviceConnectionState {
        // Truthfully reports pairing unavailable until legitimate remote channel is established
        return RemoteDeviceConnectionState.UNAVAILABLE
    }

    override fun revokeAccess(deviceId: String): Boolean = true

    override fun verifyConnection(deviceId: String): RemoteDeviceConnectionState {
        return RemoteDeviceConnectionState.DISCONNECTED
    }
}
