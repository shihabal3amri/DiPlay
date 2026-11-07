package com.shilapi.xcertplay.hud

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The cluster's resource channel on the AutoContainer binder, spoken from the shell user. The service takes
 * `sendInfo2(8, message)` and answers on the callback registered with `registerCallback`, both on the interface that
 * ClusterDebug and BYD's own tools use (see [BydClusterThemeMessage]); `sendInfo(1000, code, "")` asks the cluster
 * to reload its interface. Transaction numbers are read from the firmware's own interface classes, because BYD
 * renumbers them between releases. The shell user is not an app: hidden framework classes are reachable from it.
 */
internal class BydClusterThemeLink private constructor(
    private val service: IBinder,
    private val sendInfo2: Int,
    private val sendInfo: Int,
    private val registerCallback: Int,
    private val receivedInfo2: Int,
) : BydClusterThemeChannel {
    private val replies = LinkedBlockingQueue<BydClusterThemeMessage.Reply>()

    private val callback: Binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != receivedInfo2) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(CALLBACK)
            val type = data.readInt()
            val bytes = data.createByteArray()
            if (type == BydClusterThemeMessage.TYPE && bytes != null) BydClusterThemeMessage.parse(bytes)?.let(replies::offer)
            reply?.writeNoException()
            return true
        }
    }.apply { attachInterface(null, CALLBACK) }

    override fun active(): BydClusterThemeChannel.Active? {
        replies.clear()
        send(BydClusterThemeMessage.queryActive())
        val reply = await(BydClusterThemeMessage.QUERY_REPLY, BydClusterThemeMessage.QUERY_ACTIVE) ?: return null
        return BydClusterThemeChannel.Active(reply.ints[0], reply.strings[0])
    }

    override fun select(name: String?): Boolean {
        replies.clear()
        send(if (name == null) BydClusterThemeMessage.selectDefault() else BydClusterThemeMessage.select(name))
        return await(BydClusterThemeMessage.SELECT_REPLY, ANY_SUB) != null
    }

    override fun refresh(code: Int): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(SERVICE)
            data.writeInt(REFRESH_TYPE)
            data.writeInt(code)
            data.writeString("")
            if (!service.transact(sendInfo, data, reply, 0)) return false
            reply.readException()
            true
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun register() = call(registerCallback, callback, null)

    private fun send(message: ByteArray) = call(sendInfo2, null, message)

    /** The same parcel layout as the helper that already selects a theme on a Han: token, [binder], type, bytes. */
    private fun call(code: Int, binder: IBinder?, message: ByteArray?): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SERVICE)
            if (binder != null) data.writeStrongBinder(binder)
            data.writeInt(BydClusterThemeMessage.TYPE)
            data.writeByteArray(message)
            check(service.transact(code, data, reply, 0)) { "Unsupported transaction" }
            reply.readException()
            return reply.readInt()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun await(cmd: Int, sub: Int): BydClusterThemeMessage.Reply? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REPLY_WAIT_MILLIS)
        while (true) {
            val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (left <= 0) return null
            val reply = replies.poll(left, TimeUnit.MILLISECONDS) ?: return null
            if (reply.cmd == cmd && (sub == ANY_SUB || reply.sub == sub)) return reply
        }
    }

    companion object {
        private const val SERVICE = "android.os.IAutoContainer"
        private const val CALLBACK = "android.os.IContainerCallback"
        private const val REFRESH_TYPE = 1000
        private const val ANY_SUB = -1
        private const val REPLY_WAIT_MILLIS = 6_000L

        /** Connects and registers for replies; null when the head unit has no such service or interface. */
        fun connect(): BydClusterThemeLink? = try {
            val service = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "AutoContainer") as? IBinder
            if (service == null) {
                null
            } else {
                BydClusterThemeLink(
                    service,
                    transaction("android.os.IAutoContainer\$Stub", "sendInfo2"),
                    transaction("android.os.IAutoContainer\$Stub", "sendInfo"),
                    transaction("android.os.IAutoContainer\$Stub", "registerCallback"),
                    transaction("android.os.IContainerCallback\$Stub", "receivedInfo2"),
                ).also { link ->
                    startBinderThread()
                    link.register()
                }
            }
        } catch (_: Throwable) {
            null
        }

        private fun transaction(stub: String, method: String): Int =
            Class.forName(stub).getDeclaredField("TRANSACTION_$method").apply { isAccessible = true }.getInt(null)

        /** Replies come in on binder threads; one is kept to serve them, as the framework's own tools do. */
        private fun startBinderThread() {
            Thread({
                runCatching { Class.forName("com.android.internal.os.BinderInternal").getMethod("joinThreadPool").invoke(null) }
            }, "adas-theme-binder").apply { isDaemon = true; start() }
        }
    }
}
