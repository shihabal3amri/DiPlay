package com.shilapi.xcertplay

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Opens DiPlay when the selected iPhone joins the head unit's Bluetooth. Head units usually wake from
 * sleep instead of rebooting, so the boot trigger alone never fires on an ordinary drive.
 */
class BluetoothAutoStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        val address = device?.address ?: return
        AutoStartLauncher.onTrigger(context, AutoStartPolicy.Trigger.BLUETOOTH, address)
    }
}
