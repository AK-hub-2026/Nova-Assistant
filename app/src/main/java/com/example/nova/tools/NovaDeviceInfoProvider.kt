package com.example.nova.tools

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.example.nova.core.BatteryChargingSource
import com.example.nova.core.BatteryChargingState
import com.example.nova.core.DeviceFieldState
import com.example.nova.core.DeviceFieldStatus
import com.example.nova.core.NetworkConnectivityState
import com.example.nova.core.NetworkTransportType
import com.example.nova.core.NovaDeviceInfo
import com.example.nova.security.PermissionAuditor

/**
 * Raw readings obtained from the Android framework at runtime.
 * Nullable fields represent values that the OS could not provide or that failed to query;
 * they are mapped into explicit [DeviceFieldState.Unavailable] states rather than 0 or fake values.
 */
data class RawAndroidDeviceReadings(
    val batteryIntentPresent: Boolean,
    val batteryLevel: Int?,
    val batteryScale: Int?,
    val batteryStatusConstant: Int?,
    val batteryPluggedConstant: Int?,
    val totalRamBytes: Long?,
    val availableRamBytes: Long?,
    val isLowRamDevice: Boolean?,
    val storageScopeLabel: String?,
    val totalStorageBytes: Long?,
    val availableStorageBytes: Long?,
    val androidRelease: String?,
    val sdkInt: Int?,
    val manufacturer: String?,
    val model: String?,
    val connectivityManagerAvailable: Boolean,
    val hasActiveNetwork: Boolean?,
    val hasInternetCapability: Boolean?,
    val activeTransportConstants: Set<Int>?,
    val hasFlashlightHardware: Boolean?
)

/**
 * Abstraction over Android system services used by [NovaDeviceInfoProvider].
 * Production code uses [AndroidFrameworkDeviceDataSource] exclusively.
 */
interface DeviceInfoDataSource {
    fun captureRawReadings(): RawAndroidDeviceReadings
}

/**
 * Production [DeviceInfoDataSource] querying real Android OS APIs:
 * - [Intent.ACTION_BATTERY_CHANGED] & [BatteryManager]
 * - [ActivityManager.MemoryInfo]
 * - [StatFs] on [Environment.getDataDirectory]
 * - [Build.VERSION] & [Build.MODEL]
 * - [ConnectivityManager] & [NetworkCapabilities]
 *
 * Strictly avoids sensitive device identifiers (no IMEI, serial, MAC, SSID, or IP).
 */
class AndroidFrameworkDeviceDataSource(
    context: Context
) : DeviceInfoDataSource {
    private val appContext = context.applicationContext

    override fun captureRawReadings(): RawAndroidDeviceReadings {
        // 1. Battery via sticky ACTION_BATTERY_CHANGED broadcast
        val batteryIntent: Intent? = runCatching {
            appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)?.takeIf { it >= 0 }
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1)?.takeIf { it > 0 }
        val statusConst = batteryIntent?.getIntExtra(
            BatteryManager.EXTRA_STATUS,
            BatteryManager.BATTERY_STATUS_UNKNOWN
        )?.takeIf { it != BatteryManager.BATTERY_STATUS_UNKNOWN && it >= 0 }
        val pluggedConst = if (batteryIntent != null && batteryIntent.hasExtra(BatteryManager.EXTRA_PLUGGED)) {
            batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1).takeIf { it >= 0 }
        } else {
            null
        }

        // 2. RAM via ActivityManager.MemoryInfo
        val activityManager = runCatching {
            appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        }.getOrNull()

        var totalRam: Long? = null
        var availRam: Long? = null
        var lowRamFlag: Boolean? = null
        if (activityManager != null) {
            runCatching {
                val memInfo = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memInfo)
                if (memInfo.totalMem > 0L) {
                    totalRam = memInfo.totalMem
                }
                if (memInfo.availMem >= 0L && memInfo.totalMem > 0L) {
                    availRam = memInfo.availMem
                }
                lowRamFlag = activityManager.isLowRamDevice
            }
        }

        // 3. Storage via StatFs (Internal Data Partition)
        var storageScope: String? = null
        var totalStorage: Long? = null
        var availStorage: Long? = null
        runCatching {
            val dataDir = Environment.getDataDirectory()
            val statFs = StatFs(dataDir.absolutePath)
            val total = statFs.totalBytes
            val avail = statFs.availableBytes
            if (total > 0L && avail >= 0L) {
                storageScope = "Internal Data Partition (${dataDir.absolutePath})"
                totalStorage = total
                availStorage = avail
            }
        }.onFailure {
            runCatching {
                val filesDir = appContext.filesDir
                if (filesDir != null) {
                    val statFs = StatFs(filesDir.absolutePath)
                    val total = statFs.totalBytes
                    val avail = statFs.availableBytes
                    if (total > 0L && avail >= 0L) {
                        storageScope = "App Internal Storage Scope"
                        totalStorage = total
                        availStorage = avail
                    }
                }
            }
        }

        // 4. Safe Android & Device Build metadata
        val release = Build.VERSION.RELEASE?.trim()?.takeIf {
            it.isNotEmpty() && !it.equals(Build.UNKNOWN, ignoreCase = true)
        }
        val sdk = Build.VERSION.SDK_INT.takeIf { it > 0 }
        val manufacturer = Build.MANUFACTURER?.trim()?.takeIf {
            it.isNotEmpty() && !it.equals(Build.UNKNOWN, ignoreCase = true)
        }
        val model = Build.MODEL?.trim()?.takeIf {
            it.isNotEmpty() && !it.equals(Build.UNKNOWN, ignoreCase = true)
        }

        // 5. Network via ConnectivityManager & NetworkCapabilities
        val connectivityManager = runCatching {
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        }.getOrNull()

        var hasActiveNet: Boolean? = null
        var hasInternetCap: Boolean? = null
        var transports: Set<Int>? = null

        if (connectivityManager != null) {
            runCatching {
                val activeNetwork = connectivityManager.activeNetwork
                if (activeNetwork == null) {
                    hasActiveNet = false
                    hasInternetCap = false
                    transports = emptySet()
                } else {
                    hasActiveNet = true
                    val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
                    if (caps != null) {
                        hasInternetCap = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        val detectedTransports = mutableSetOf<Int>()
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            detectedTransports.add(NetworkCapabilities.TRANSPORT_WIFI)
                        }
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                            detectedTransports.add(NetworkCapabilities.TRANSPORT_CELLULAR)
                        }
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                            detectedTransports.add(NetworkCapabilities.TRANSPORT_ETHERNET)
                        }
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                            detectedTransports.add(NetworkCapabilities.TRANSPORT_VPN)
                        }
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) {
                            detectedTransports.add(NetworkCapabilities.TRANSPORT_BLUETOOTH)
                        }
                        transports = detectedTransports
                    }
                }
            }
        }

        // 6. Optional hardware capability
        val hasFlash = runCatching {
            PermissionAuditor.hasFlashlightHardware(appContext)
        }.getOrNull()

        return RawAndroidDeviceReadings(
            batteryIntentPresent = batteryIntent != null,
            batteryLevel = level,
            batteryScale = scale,
            batteryStatusConstant = statusConst,
            batteryPluggedConstant = pluggedConst,
            totalRamBytes = totalRam,
            availableRamBytes = availRam,
            isLowRamDevice = lowRamFlag,
            storageScopeLabel = storageScope,
            totalStorageBytes = totalStorage,
            availableStorageBytes = availStorage,
            androidRelease = release,
            sdkInt = sdk,
            manufacturer = manufacturer,
            model = model,
            connectivityManagerAvailable = connectivityManager != null,
            hasActiveNetwork = hasActiveNet,
            hasInternetCapability = hasInternetCap,
            activeTransportConstants = transports,
            hasFlashlightHardware = hasFlash
        )
    }
}

sealed class DeviceInfoQueryOutcome {
    data class Success(val deviceInfo: NovaDeviceInfo) : DeviceInfoQueryOutcome()
    data class ProviderFailure(val errorDescription: String) : DeviceInfoQueryOutcome()
}

/**
 * M4 — Real Device Information Provider.
 * Maps raw Android framework readings into [NovaDeviceInfo] with explicit per-field
 * availability states ([DeviceFieldState.Available] vs [DeviceFieldState.Unavailable]).
 * Never invents fallback numbers (such as 0%, 0 MB, or fake device names).
 */
class NovaDeviceInfoProvider(
    private val dataSource: DeviceInfoDataSource
) {
    constructor(context: Context) : this(AndroidFrameworkDeviceDataSource(context))

    fun queryDeviceInfo(): DeviceInfoQueryOutcome {
        val raw = try {
            dataSource.captureRawReadings()
        } catch (e: Exception) {
            return DeviceInfoQueryOutcome.ProviderFailure(
                "Device information provider failed: ${e.message ?: e.javaClass.simpleName}"
            )
        }

        val info = mapRawReadingsToDeviceInfo(raw, System.currentTimeMillis())

        // If every primary subsystem failed to return any data at all, report a real provider failure
        val anyPrimaryFieldAvailable = info.batteryPercentage.isAvailable ||
            info.totalRamBytes.isAvailable ||
            info.totalStorageBytes.isAvailable ||
            info.androidVersionRelease.isAvailable ||
            info.sdkInt.isAvailable ||
            info.networkConnectivity.isAvailable

        if (!anyPrimaryFieldAvailable) {
            return DeviceInfoQueryOutcome.ProviderFailure(
                "All Android device telemetry subsystems returned unavailable states."
            )
        }

        return DeviceInfoQueryOutcome.Success(info)
    }

    companion object {
        fun mapRawReadingsToDeviceInfo(
            raw: RawAndroidDeviceReadings,
            timestampMs: Long = System.currentTimeMillis()
        ): NovaDeviceInfo {
            // 1. Battery percentage
            val batteryPercentState: DeviceFieldState<Int> = if (
                raw.batteryIntentPresent &&
                raw.batteryLevel != null &&
                raw.batteryScale != null &&
                raw.batteryLevel >= 0 &&
                raw.batteryScale > 0
            ) {
                val pct = ((raw.batteryLevel.toDouble() / raw.batteryScale.toDouble()) * 100.0)
                    .toInt()
                    .coerceIn(0, 100)
                DeviceFieldState.Available(pct)
            } else {
                DeviceFieldState.Unavailable("Unavailable")
            }

            // 2. Charging state
            val chargingState: DeviceFieldState<BatteryChargingState> = when (raw.batteryStatusConstant) {
                BatteryManager.BATTERY_STATUS_CHARGING ->
                    DeviceFieldState.Available(BatteryChargingState.CHARGING)
                BatteryManager.BATTERY_STATUS_DISCHARGING ->
                    DeviceFieldState.Available(BatteryChargingState.DISCHARGING)
                BatteryManager.BATTERY_STATUS_FULL ->
                    DeviceFieldState.Available(BatteryChargingState.FULL)
                BatteryManager.BATTERY_STATUS_NOT_CHARGING ->
                    DeviceFieldState.Available(BatteryChargingState.NOT_CHARGING)
                else -> DeviceFieldState.Unavailable("Unavailable")
            }

            // 3. Charging source
            val chargingSourceState: DeviceFieldState<BatteryChargingSource> = when (raw.batteryPluggedConstant) {
                null -> DeviceFieldState.Unavailable("Unavailable")
                0 -> DeviceFieldState.Available(BatteryChargingSource.UNPLUGGED)
                else -> {
                    val p = raw.batteryPluggedConstant
                    when {
                        (p and BatteryManager.BATTERY_PLUGGED_AC) != 0 ->
                            DeviceFieldState.Available(BatteryChargingSource.AC)
                        (p and BatteryManager.BATTERY_PLUGGED_USB) != 0 ->
                            DeviceFieldState.Available(BatteryChargingSource.USB)
                        (p and BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0 ->
                            DeviceFieldState.Available(BatteryChargingSource.WIRELESS)
                        (p and BatteryManager.BATTERY_PLUGGED_DOCK) != 0 ->
                            DeviceFieldState.Available(BatteryChargingSource.DOCK)
                        else -> DeviceFieldState.Unavailable("Unavailable")
                    }
                }
            }

            // 4. Memory (RAM)
            val totalRamState: DeviceFieldState<Long> = if (raw.totalRamBytes != null && raw.totalRamBytes > 0L) {
                DeviceFieldState.Available(raw.totalRamBytes)
            } else {
                DeviceFieldState.Unavailable("Unavailable")
            }

            val availRamState: DeviceFieldState<Long> = if (
                raw.availableRamBytes != null &&
                raw.availableRamBytes >= 0L &&
                raw.totalRamBytes != null &&
                raw.totalRamBytes > 0L
            ) {
                DeviceFieldState.Available(raw.availableRamBytes)
            } else {
                DeviceFieldState.Unavailable("Unavailable")
            }

            val lowRamState: DeviceFieldState<Boolean> = if (raw.isLowRamDevice != null) {
                DeviceFieldState.Available(raw.isLowRamDevice)
            } else {
                DeviceFieldState.Unavailable("Unavailable")
            }

            // 5. Storage
            val storageScopeState: DeviceFieldState<String> = if (!raw.storageScopeLabel.isNullOrBlank()) {
                DeviceFieldState.Available(raw.storageScopeLabel)
            } else {
                DeviceFieldState.Unavailable("Unavailable")
            }

            val totalStorageState: DeviceFieldState<Long> = if (raw.totalStorageBytes != null && raw.totalStorageBytes > 0L) {
                DeviceFieldState.Available(raw.totalStorageBytes)
            } else {
                DeviceFieldState.Unavailable("Unavailable")
            }

            val availStorageState: DeviceFieldState<Long> = if (
                raw.availableStorageBytes != null &&
                raw.availableStorageBytes >= 0L &&
                raw.totalStorageBytes != null &&
                raw.totalStorageBytes > 0L
            ) {
                DeviceFieldState.Available(raw.availableStorageBytes)
            } else {
                DeviceFieldState.Unavailable("Unavailable")
            }

            // 6. Android & safe Device model
            val androidReleaseState: DeviceFieldState<String> = if (!raw.androidRelease.isNullOrBlank()) {
                DeviceFieldState.Available(raw.androidRelease)
            } else {
                DeviceFieldState.Unavailable("Unavailable")
            }

            val sdkIntState: DeviceFieldState<Int> = if (raw.sdkInt != null && raw.sdkInt > 0) {
                DeviceFieldState.Available(raw.sdkInt)
            } else {
                DeviceFieldState.Unavailable("Unavailable")
            }

            val safeModelState: DeviceFieldState<String> = run {
                val mfg = raw.manufacturer?.trim().orEmpty()
                val mdl = raw.model?.trim().orEmpty()
                val combined = when {
                    mfg.isNotEmpty() && mdl.isNotEmpty() ->
                        if (mdl.startsWith(mfg, ignoreCase = true)) mdl else "$mfg $mdl"
                    mdl.isNotEmpty() -> mdl
                    mfg.isNotEmpty() -> mfg
                    else -> ""
                }
                if (combined.isNotBlank()) {
                    DeviceFieldState.Available(combined)
                } else {
                    DeviceFieldState.Unavailable("Unavailable")
                }
            }

            // 7. Network connectivity & transport
            val networkConnectivityState: DeviceFieldState<NetworkConnectivityState> = if (
                !raw.connectivityManagerAvailable || raw.hasActiveNetwork == null
            ) {
                DeviceFieldState.Unavailable("Unavailable")
            } else if (raw.hasActiveNetwork && raw.hasInternetCapability != false) {
                DeviceFieldState.Available(NetworkConnectivityState.CONNECTED)
            } else {
                DeviceFieldState.Available(NetworkConnectivityState.DISCONNECTED)
            }

            val networkTransportState: DeviceFieldState<NetworkTransportType> = when {
                !raw.connectivityManagerAvailable || raw.hasActiveNetwork == null ->
                    DeviceFieldState.Unavailable("Unavailable")
                !raw.hasActiveNetwork ->
                    DeviceFieldState.Available(NetworkTransportType.NONE)
                raw.activeTransportConstants == null ->
                    DeviceFieldState.Unavailable("Unavailable")
                raw.activeTransportConstants.contains(NetworkCapabilities.TRANSPORT_WIFI) ->
                    DeviceFieldState.Available(NetworkTransportType.WIFI)
                raw.activeTransportConstants.contains(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                    DeviceFieldState.Available(NetworkTransportType.CELLULAR)
                raw.activeTransportConstants.contains(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                    DeviceFieldState.Available(NetworkTransportType.ETHERNET)
                raw.activeTransportConstants.contains(NetworkCapabilities.TRANSPORT_VPN) ->
                    DeviceFieldState.Available(NetworkTransportType.VPN)
                raw.activeTransportConstants.contains(NetworkCapabilities.TRANSPORT_BLUETOOTH) ->
                    DeviceFieldState.Available(NetworkTransportType.BLUETOOTH)
                raw.activeTransportConstants.isEmpty() ->
                    DeviceFieldState.Unavailable("Unavailable")
                else ->
                    DeviceFieldState.Available(NetworkTransportType.OTHER)
            }

            val flashlightState: DeviceFieldState<Boolean> = if (raw.hasFlashlightHardware != null) {
                DeviceFieldState.Available(raw.hasFlashlightHardware)
            } else {
                DeviceFieldState.Unavailable("Unavailable", DeviceFieldStatus.UNSUPPORTED)
            }

            return NovaDeviceInfo(
                batteryPercentage = batteryPercentState,
                chargingState = chargingState,
                chargingSource = chargingSourceState,
                totalRamBytes = totalRamState,
                availableRamBytes = availRamState,
                isLowRamDevice = lowRamState,
                storageScopeDescription = storageScopeState,
                totalStorageBytes = totalStorageState,
                availableStorageBytes = availStorageState,
                androidVersionRelease = androidReleaseState,
                sdkInt = sdkIntState,
                safeDeviceModel = safeModelState,
                networkConnectivity = networkConnectivityState,
                networkTransport = networkTransportState,
                hasFlashlightHardware = flashlightState,
                capturedAtMs = timestampMs
            )
        }
    }
}
