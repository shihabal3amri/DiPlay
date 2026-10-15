package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import com.geely.lib.oneosapi.input.IInputListener
import com.geely.lib.oneosapi.input.IInputManager
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.network.GeelyHeadUnit

/**
 * Geely/Lynk steering-wheel media keys.
 *
 * The head unit maps the wheel's keys to vendor key codes and routes them only through its own input
 * stack - no standard Android KeyEvent is produced - so a media session or accessibility service
 * never sees them.
 *
 * Two firmware paths are tried in order:
 * 1. `com.geely.inputservice.InputService` directly exposes the `IInputManager` AIDL interface.
 * 2. `com.geely.service.oneosapi.OneOSApiService` is a service manager; its binder implements
 *    `IServiceManager` and we obtain the input manager through a raw `getService` transaction
 *    (transaction 2, service ID 8), then use raw `registerListener`/`unregisterListener`
 *    transactions (codes 3 and 4) on the returned binder.
 *
 * With [AirPlayPersistence.loadCarBluetoothAudio] on, the car's own Bluetooth (AVRCP) control drives
 * the iPhone's music, so forwarding the media keys too would double-fire; only the microphone key is
 * registered then, for the long-press Siri shortcut.
 *
 * The microphone key's long press is answered by the car's HiCar app, which registers for the same
 * code and cannot be intercepted from here; disable HiCar over ADB if Siri should answer instead
 * (see docs/COMPATIBILITY.md).
 *
 * Silently does nothing off this head unit ([GeelyHeadUnit.detected]).
 */
internal object GeelyWheelKeys {
    private const val TAG = "DiPlay-GeelyWheel"

    private const val INPUT_SERVICE_PACKAGE = "com.geely.inputservice"
    private const val INPUT_SERVICE_CLASS = "com.geely.inputservice.InputService"

    private const val ONE_OS_PACKAGE = "com.geely.service.oneosapi"
    private const val ONE_OS_SERVICE = "com.geely.service.oneosapi.OneOSApiService"

    // Raw binder transactions for firmware that only exposes the OneOS service manager.
    private const val SERVICE_MANAGER_DESCRIPTOR = "com.geely.lib.oneosapi.IServiceManager"
    private const val INPUT_MANAGER_DESCRIPTOR = "com.geely.lib.oneosapi.input.IInputManager"
    private const val TRANSACTION_GET_SERVICE = 2
    private const val TRANSACTION_REGISTER_LISTENER = 3
    private const val TRANSACTION_UNREGISTER_LISTENER = 4
    private const val INPUT_SERVICE_ID = 8

    private const val RETRY_MILLIS = 3_000L

    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var bound = false
    private var usingOneOs = false
    private var manager: IInputManager? = null
    private var oneOsInputBinder: IBinder? = null
    private var oneOsServiceManagerBinder: IBinder? = null
    private var listener: IInputListener? = null
    private var oneOsListenerBinder: IBinder? = null

    /** Binds while a CarPlay session is active; safe to call repeatedly. */
    @Synchronized
    fun start(context: Context) {
        if (!GeelyHeadUnit.detected) return
        appContext = context.applicationContext
        if (bound) return
        bindLocked()
    }

    /** Unbinds when the CarPlay session ends. */
    @Synchronized
    fun stop() {
        val packageName = appContext?.packageName
        listener?.let { registered ->
            if (packageName != null) runCatching { manager?.unregisterListener(registered, packageName) }
        }
        if (usingOneOs) {
            oneOsListenerBinder?.let { binder ->
                if (packageName != null) {
                    oneOsTransact(
                        oneOsInputBinder, TRANSACTION_UNREGISTER_LISTENER, INPUT_MANAGER_DESCRIPTOR,
                    ) { parcel ->
                        parcel.writeStrongBinder(binder)
                        parcel.writeString(packageName)
                    }
                }
            }
            oneOsInputBinder = null
            oneOsListenerBinder = null
            oneOsServiceManagerBinder = null
        }
        usingOneOs = false
        listener = null
        manager = null
        handler.removeCallbacksAndMessages(null)
        val context = appContext
        if (bound && context != null) runCatching { context.unbindService(connection) }
        bound = false
    }

    private fun bindLocked() {
        val context = appContext ?: return
        // Try the direct input service first; it needs no extra binder hop.
        val directIntent = Intent().setComponent(ComponentName(INPUT_SERVICE_PACKAGE, INPUT_SERVICE_CLASS))
        bound = runCatching { context.bindService(directIntent, connection, Context.BIND_AUTO_CREATE) }
            .getOrDefault(false)
        if (bound) {
            usingOneOs = false
            Log.i(TAG, "bind $INPUT_SERVICE_CLASS requested")
            return
        }
        // Fall back to the OneOS service manager.
        val oneOsIntent = Intent().setComponent(ComponentName(ONE_OS_PACKAGE, ONE_OS_SERVICE))
        bound = runCatching { context.bindService(oneOsIntent, connection, Context.BIND_AUTO_CREATE) }
            .getOrDefault(false)
        usingOneOs = bound
        Log.i(TAG, "bind $ONE_OS_SERVICE requested=$bound usingOneOs=$usingOneOs")
        if (!bound) Log.w(TAG, "no Geely input service available on this firmware")
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val context = appContext ?: return
            if (usingOneOs) connectOneOs(service) else connectDirectAidl(service, context)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            val retry: Boolean
            synchronized(this@GeelyWheelKeys) {
                manager = null
                listener = null
                oneOsInputBinder = null
                oneOsListenerBinder = null
                oneOsServiceManagerBinder = null
                retry = appContext != null
            }
            if (retry) {
                handler.postDelayed({
                    synchronized(this@GeelyWheelKeys) {
                        if (appContext != null && manager == null && oneOsInputBinder == null) {
                            bound = false
                            bindLocked()
                        }
                    }
                }, RETRY_MILLIS)
            }
        }
    }

    private fun connectDirectAidl(service: IBinder?, context: Context) {
        val input = IInputManager.Stub.asInterface(service) ?: return
        val registered = createListener()
        synchronized(this) {
            manager = input
            listener = registered
        }
        registerWithAidl(input, registered, context)
    }

    private fun connectOneOs(service: IBinder?) {
        if (service == null) return
        val inputBinder = oneOsTransact(service, TRANSACTION_GET_SERVICE, SERVICE_MANAGER_DESCRIPTOR, { parcel ->
            parcel.writeInt(INPUT_SERVICE_ID)
        }) { parcel -> parcel.readStrongBinder() } ?: run {
            Log.w(TAG, "OneOS getService returned no input binder")
            return
        }
        oneOsServiceManagerBinder = service
        oneOsInputBinder = inputBinder
        val context = appContext ?: return
        val registered = createListener()
        oneOsListenerBinder = registered.asBinder()
        val packageName = context.packageName
        val bluetoothAudio = AirPlayPersistence.loadCarBluetoothAudio(context)
        val codes = GeelyWheelKeyCodes.registeredCodes(bluetoothAudio)
        val result = oneOsTransact(inputBinder, TRANSACTION_REGISTER_LISTENER, INPUT_MANAGER_DESCRIPTOR, { parcel ->
            parcel.writeStrongBinder(registered.asBinder())
            parcel.writeString(packageName)
            parcel.writeIntArray(codes)
        }) { Unit }
        if (result != null) {
            Log.i(TAG, "OneOS registered for wheel key codes=${codes.joinToString()}")
        } else {
            Log.w(TAG, "OneOS registerListener failed")
        }
    }

    private fun registerWithAidl(input: IInputManager, registered: IInputListener, context: Context) {
        runCatching {
            val bluetoothAudio = AirPlayPersistence.loadCarBluetoothAudio(context)
            val codes = GeelyWheelKeyCodes.registeredCodes(bluetoothAudio)
            if (bluetoothAudio) {
                Log.i(TAG, "car Bluetooth audio on: media keys pass to the head unit, only the Siri key is wired")
            }
            input.registerListener(registered, context.packageName, codes)
            codes
        }
            .onSuccess { Log.i(TAG, "registered for wheel key codes=${it.joinToString()}") }
            .onFailure { Log.w(TAG, "registerListener failed", it) }
    }

    private fun createListener(): IInputListener = object : IInputListener.Stub() {
        override fun onKeyCodeEvent(keyCode: Int, event: Int, softKeyFunction: Int) {}
        override fun onShortClick(keyCode: Int, softKeyFunction: Int) {
            forward(keyCode)
        }
        override fun onHoldingPressStarted(keyCode: Int, softKeyFunction: Int) {}
        override fun onHoldingPressStopped(keyCode: Int, softKeyFunction: Int) {}
        override fun onLongPressTriggered(keyCode: Int, softKeyFunction: Int) {
            Log.i(TAG, "long press key=$keyCode func=$softKeyFunction")
            if (keyCode == GeelyWheelKeyCodes.KEY_VOICE_ASSIST) {
                Log.i(TAG, "Siri requested=${CarPlayMediaKeys.onExternalSiriKey()}")
            }
        }
        override fun onDoubleClick(keyCode: Int, softKeyFunction: Int) {}
    }

    private fun <T> oneOsTransact(
        binder: IBinder?,
        code: Int,
        descriptor: String,
        write: (Parcel) -> Unit,
        read: (Parcel) -> T,
    ): T? {
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            request.writeInterfaceToken(descriptor)
            write(request)
            if (binder?.transact(code, request, reply, 0) == true) {
                reply.readException()
                read(reply)
            } else null
        } catch (error: Exception) {
            Log.w(TAG, "OneOS input transaction failed", error)
            null
        } finally {
            reply.recycle()
            request.recycle()
        }
    }

    private fun forward(keyCode: Int) {
        val index = when (keyCode) {
            GeelyWheelKeyCodes.KEY_PLAY_PAUSE -> CarPlayMediaButton.PLAY_PAUSE
            GeelyWheelKeyCodes.KEY_NEXT -> CarPlayMediaButton.NEXT
            GeelyWheelKeyCodes.KEY_PREVIOUS -> CarPlayMediaButton.PREVIOUS
            else -> return
        }
        CarPlayMediaKeys.onExternalMediaKey(index)
    }
}
