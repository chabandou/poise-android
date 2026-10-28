package com.poise.android.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager

// Outputs that play media. Left out: the earpiece, call-only Bluetooth (SCO) and system internals
private val PICKABLE =
        setOf(
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER,
                AudioDeviceInfo.TYPE_HEARING_AID
        )

/** Names a device across reconnects, which give it a new id: Bluetooth keeps its address. */
val AudioDeviceInfo.outputKey: String
    get() = "$type:$address"

/** The connected outputs the user can send Poise to. */
fun AudioManager.pickableOutputs(): List<AudioDeviceInfo> =
        getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type in PICKABLE }.distinctBy { it.outputKey }
