package com.shilapi.xcertplay.hud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Publishes CarPlay route arrows, distance and street to BYD's native windshield HUD. */
internal object BydHudBridge {
    private const val TAG = "DiPlay-BYD-HUD"
    private const val SOMEIP_PACKAGE = "com.ts.car.someip.service"
    private const val SOMEIP_CLASS = "com.ts.car.someip.service.manager.SomeIpServerService"
    private const val SOMEIP_ACTION = "com.ts.car.someip.SomeIpServerService"
    private const val SOMEIP_TOKEN = "ts.car.someip.sdk.ISomeIpServerInterface"
    private const val CALLBACK_TOKEN = "ts.car.someip.sdk.ISomeIpCallback"
    private const val SERVICE_ID = 0x000B010A00010000L
    private const val HUD_TOPIC = 0x0004010A00018001L
    private const val TX_REGISTER_CALLBACK = 1
    private const val TX_START_SERVICE = 4
    private const val TX_FIRE_EVENT = 6
    private const val ICON_ASSET_DIR = "byd-hud-icons"

    private val lock = Any()
    private val route = BydHudRouteState()
    private var context: Context? = null
    private var binder: IBinder? = null
    private var binding = false
    private var started = false
    private var sequence = 0
    private var senderStarted = false
    private var guidanceSentLogged = false
    private var showing = false
    private var icons: Map<Int, ByteArray>? = null

    // The gateway pings registered callbacks and drops registrations that do not answer like an AIDL stub.
    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = when (code) {
            INTERFACE_TRANSACTION -> { reply?.writeString(CALLBACK_TOKEN); true }
            FIRST_CALL_TRANSACTION -> {
                data.enforceInterface(CALLBACK_TOKEN)
                reply?.writeNoException()
                reply?.writeInt(0)
                true
            }
            else -> super.onTransact(code, data, reply, flags)
        }
    }

    fun initialize(appContext: Context) = synchronized(lock) {
        if (context == null) context = appContext.applicationContext
        bindLocked()
        if (!senderStarted) {
            senderStarted = true
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "diplay-byd-hud").apply { isDaemon = true }
            }.scheduleAtFixedRate(::tick, 1, 1, TimeUnit.SECONDS)
        }
    }

    fun onFrame(frame: Iap2Frame) = synchronized(lock) {
        val change = route.accept(frame.messageId, frame.payload)
        if (frame.messageId == BydHudRouteState.ROUTE_GUIDANCE_UPDATE ||
            frame.messageId == BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE
        ) {
            Log.d(TAG, "route frame=0x${frame.messageId.toString(16)} change=$change guidance=${route.current()}")
        }
        when (change) {
            BydHudRouteChange.GUIDANCE -> sendCurrentLocked()
            BydHudRouteChange.CLEAR -> clearHudLocked()
            BydHudRouteChange.NONE -> Unit
        }
    }

    /** Clears the HUD immediately; called when DiPlay is about to be killed. */
    fun clearNow() = synchronized(lock) { clearHudLocked() }

    fun clear() = synchronized(lock) {
        if (route.clear()) clearHudLocked()
    }

    private fun tick() = synchronized(lock) {
        if (binder == null && !binding) bindLocked()
        sendCurrentLocked()
    }

    private fun enabled(): Boolean = context?.let(BydOutputSettings::enabled) ?: false

    private fun sendCurrentLocked() {
        if (binder == null || !started) return
        if (!enabled()) {
            clearHudLocked()
            return
        }
        val guidance = route.current()
        if (guidance == null) {
            clearHudLocked()
            return
        }
        val payload = BydHudPayload.guidance(
            distanceMeters = guidance.distanceMeters,
            maneuver = guidance.maneuver,
            sequence = sequence++ and 0xff,
            icon = iconFor(guidance.gaode),
            road = guidance.road,
        )
        if (sendLocked(payload) && !guidanceSentLogged) {
            guidanceSentLogged = true
            Log.i(TAG, "HUD guidance accepted $guidance")
        }
        showing = true
    }

    private fun clearHudLocked() {
        if (!showing) return
        sendLocked(BydHudPayload.clear())
        showing = false
        guidanceSentLogged = false
    }

    // Arrow-less maneuvers (roundabouts, destination) are only visible through the field-8 icon.
    private fun iconFor(gaode: Int): ByteArray? {
        if (gaode <= 0) return null
        val loaded = icons ?: loadIcons().also { icons = it }
        return loaded[gaode]
    }

    private fun loadIcons(): Map<Int, ByteArray> {
        val assets = context?.assets ?: return emptyMap()
        return runCatching {
            assets.list(ICON_ASSET_DIR).orEmpty().mapNotNull { name ->
                val code = name.removePrefix("0x").removeSuffix(".png").toIntOrNull(16) ?: return@mapNotNull null
                code to assets.open("$ICON_ASSET_DIR/$name").use { it.readBytes() }
            }.toMap()
        }.onFailure { Log.w(TAG, "HUD icons unavailable", it) }.getOrDefault(emptyMap())
    }

    private fun bindLocked() {
        val appContext = context ?: return
        if (binder != null || binding) return
        try {
            // The gateway's onUnbind requires a MIME type; a typeless bind crashes the whole SOME/IP process.
            val intent = Intent(SOMEIP_ACTION).apply {
                setClassName(SOMEIP_PACKAGE, SOMEIP_CLASS)
                type = appContext.packageName
            }
            binding = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            Log.i(TAG, "bindService=$binding")
        } catch (error: Throwable) {
            binding = false
            Log.w(TAG, "cannot bind SOME/IP service", error)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) = synchronized(lock) {
            binder = service
            binding = true
            // Also runs after a gateway restart: its registrations and started services are gone.
            val registered = transactLocked(TX_REGISTER_CALLBACK) { it.writeStrongBinder(callback) }
            started = transactLocked(TX_START_SERVICE) { it.writeLong(SERVICE_ID) } >= 0
            Log.i(TAG, "SOME/IP connected, callback=$registered started=$started")
            showing = false
            sendCurrentLocked()
        }

        // The binding stays registered and BIND_AUTO_CREATE reconnects; binding again would leak a connection.
        override fun onServiceDisconnected(name: ComponentName) = synchronized(lock) {
            binder = null
            started = false
        }

        override fun onBindingDied(name: ComponentName) = synchronized(lock) { resetBindingLocked() }
        override fun onNullBinding(name: ComponentName) = synchronized(lock) { resetBindingLocked() }
    }

    private inline fun transactLocked(code: Int, write: (Parcel) -> Unit): Int {
        val target = binder ?: return -1
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(SOMEIP_TOKEN)
            write(data)
            target.transact(code, data, reply, 0)
            reply.readException()
            reply.readInt()
        } catch (error: Throwable) {
            Log.w(TAG, "SOME/IP transaction $code failed", error)
            -1
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun sendLocked(payload: ByteArray): Boolean {
        if (binder == null) return false
        val result = transactLocked(TX_FIRE_EVENT) {
            it.writeInt(1)
            it.writeLong(HUD_TOPIC)
            it.writeLong(System.currentTimeMillis())
            it.writeInt(payload.size)
            it.writeByteArray(payload)
        }
        if (result < 0) {
            Log.w(TAG, "HUD send result=$result")
            if (binder?.isBinderAlive != true) resetBindingLocked()
        }
        return result >= 0
    }

    private fun resetBindingLocked() {
        val appContext = context
        if (appContext != null && binding) {
            runCatching { appContext.unbindService(connection) }
        }
        binder = null
        binding = false
        started = false
        showing = false
        guidanceSentLogged = false
    }
}
