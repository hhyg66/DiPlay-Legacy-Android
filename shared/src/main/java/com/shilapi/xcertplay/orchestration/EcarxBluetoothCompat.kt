package com.shilapi.xcertplay.orchestration

import android.content.Context
import android.util.Log
import java.util.Locale

/**
 * Compatibility bridge for Ecarx/Geely head units whose vendor Bluetooth service
 * is usable by the native car apps while Android's public BluetoothManager is
 * disabled or reports an all-zero adapter address.
 *
 * Reflection is intentional: the vendor XUI classes are not part of the public
 * DiPlay build and must not become a hard compile-time dependency.
 */
internal class EcarxBluetoothCompat(private val context: Context) {
    private companion object {
        const val TAG = "EcarxBluetoothCompat"
        const val BT_CLASS = "com.ecarx.xui.adaptapi.bt.Bt"
        val MAC = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
        val INVALID = setOf("00:00:00:00:00:00", "02:00:00:00:00:00")
    }

    private val bt: Any? by lazy {
        runCatching {
            Class.forName(BT_CLASS).getMethod("getInstance").invoke(null)
        }.onFailure {
            Log.i(TAG, "Ecarx XUI Bluetooth API unavailable: ${it.javaClass.simpleName}")
        }.getOrNull()
    }

    fun isAvailable(): Boolean = bt != null

    fun isReady(): Boolean {
        val value = callBoolean(bt, "isBtReady", "isReady", "isBluetoothReady")
        if (value != null) return value
        val state = callInt(bt, "getBtState", "getState", "getBluetoothState")
        return state != null && state != 0
    }

    /** Host Bluetooth MAC when the vendor layer exposes it. */
    fun hostAddress(): String? {
        val candidates = listOf(
            callString(bt, "getAddress", "getBtAddress", "getBluetoothAddress"),
            callString(callObject(bt, "getBtSettings", "getSettings"), "getAddress", "getBtAddress", "getBluetoothAddress"),
        )
        return candidates.firstOrNull(::validMac)
    }

    /**
     * Returns addresses exposed by the vendor A2DP/HFP/settings APIs.
     * The native multimedia app on this platform uses these same Ecarx APIs.
     */
    fun connectedAddresses(): Set<String> {
        val out = linkedSetOf<String>()
        val objects = listOf(
            bt,
            callObject(bt, "getBtSettings", "getSettings"),
            callObject(bt, "getA2dp", "getA2dpInstance", "getBtA2dp"),
            callObject(bt, "getHfp", "getHfpInstance", "getBtHfp"),
            callObject(bt, "getAvrcp", "getAvrcpInstance", "getBtAvrcp"),
        )
        val methodNames = arrayOf(
            "getA2dpConnectedAddress",
            "getHfpConnectedAddress",
            "getAvrcpConnectedAddress",
            "getConnectedAddress",
            "getConnectedDeviceAddress",
            "getBtRemoteDeviceAddress",
            "getRemoteDeviceAddress",
            "getBtAddress",
        )
        for (obj in objects) {
            for (name in methodNames) {
                val value = callString(obj, name)
                if (validMac(value)) out += value!!.uppercase(Locale.US)
            }
        }
        return out
    }

    /**
     * Best-effort iPhone address. Ecarx versions differ, so try connected
     * profiles first and then a few device-name/address APIs.
     */
    fun iphoneAddress(): String? {
        connectedAddresses().firstOrNull()?.let { return it }

        val objects = listOf(
            bt,
            callObject(bt, "getBtSettings", "getSettings"),
            callObject(bt, "getA2dp", "getA2dpInstance", "getBtA2dp"),
            callObject(bt, "getHfp", "getHfpInstance", "getBtHfp"),
        )
        val nameMethods = arrayOf(
            "getBtRemoteDeviceName",
            "getRemoteDeviceName",
            "getConnectedDeviceName",
        )
        val addressMethods = arrayOf(
            "getBtRemoteDeviceAddress",
            "getRemoteDeviceAddress",
            "getConnectedDeviceAddress",
        )
        for (obj in objects) {
            val name = nameMethods.firstNotNullOfOrNull { callString(obj, it) }
            val address = addressMethods.firstNotNullOfOrNull { callString(obj, it) }
            if (validMac(address) && (name == null || name.contains("iPhone", true))) {
                return address!!.uppercase(Locale.US)
            }
        }
        return null
    }

    private fun validMac(value: String?): Boolean =
        value != null && MAC.matches(value) && !INVALID.contains(value.uppercase(Locale.US))

    private fun callObject(target: Any?, vararg names: String): Any? {
        if (target == null) return null
        for (name in names) {
            runCatching {
                target.javaClass.methods.firstOrNull {
                    it.name == name && it.parameterTypes.isEmpty()
                }?.invoke(target)
            }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun callString(target: Any?, vararg names: String): String? {
        val value = callObject(target, *names) ?: return null
        return value as? String
    }

    private fun callBoolean(target: Any?, vararg names: String): Boolean? {
        val value = callObject(target, *names) ?: return null
        return value as? Boolean
    }

    private fun callInt(target: Any?, vararg names: String): Int? {
        val value = callObject(target, *names) ?: return null
        return when (value) {
            is Int -> value
            is Number -> value.toInt()
            else -> null
        }
    }
}
